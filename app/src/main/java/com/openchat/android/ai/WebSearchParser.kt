package com.openchat.android.ai

/**
 * Pure models + HTML parsers for the DuckDuckGo endpoints — no network, no
 * Android imports, JVM-tested ([WebSearchParserTest]). Regex-based on
 * purpose: the endpoints serve stable, server-rendered markup and a tiny
 * parser avoids shipping a full HTML dependency.
 */

/** One search hit (shared by the network searcher and the parser tests). */
data class SearchHit(val title: String, val url: String, val snippet: String)

object WebSearchParser {

    /** Unwraps a DuckDuckGo redirect link (`//duckduckgo.com/l/?uddg=<enc>`) to the target URL. */
    fun unwrapUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("//")) url = "https:$url"
        val marker = "uddg="
        val idx = url.indexOf(marker)
        if (idx >= 0) {
            val tail = url.substring(idx + marker.length)
            val end = tail.indexOf('&')
            val encoded = if (end >= 0) tail.substring(0, end) else tail
            runCatching { return java.net.URLDecoder.decode(encoded, "UTF-8") }
        }
        return url
    }

    /** Removes tags + decodes the handful of entities DDG uses. */
    fun stripHtml(raw: String): String = raw
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Parses html.duckduckgo.com/html — result anchors carry
     * `class="result__a"` (title + redirect href) and snippets
     * `class="result__snippet"`.
     */
    fun parseHtml(html: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val anchorRe = Regex(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val anchors = anchorRe.findAll(html).toList()
        val snippetRe = Regex(
            "<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val snippets = snippetRe.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
        anchors.forEachIndexed { i, m ->
            val url = unwrapUrl(m.groupValues[1])
            val title = stripHtml(m.groupValues[2])
            if (url.startsWith("http") && title.isNotBlank()) {
                hits.add(SearchHit(title = title, url = url, snippet = snippets.getOrElse(i) { "" }))
            }
        }
        return hits
    }

    /**
     * Parses lite.duckduckgo.com/lite — a table of plain links
     * (`<a class="result-link" href="...">`); falls back to any result-table
     * anchor with an http(s) href when the known shape changes.
     */
    fun parseLite(html: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val linkRe = Regex(
            "<a[^>]*href=\"(http[^\"]+)\"[^>]*class=\"result-link\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val links = linkRe.findAll(html).toList()
        if (links.isEmpty()) {
            // Fallback shape: any result-table anchor with an http(s) href.
            val any = Regex("<a[^>]*href=\"(http[^\"]+)\"[^>]*>([^<]{4,})</a>")
            any.findAll(html).forEach { m ->
                val url = unwrapUrl(m.groupValues[1])
                val title = stripHtml(m.groupValues[2])
                if (title.isNotBlank() && !url.contains("duckduckgo.com")) {
                    hits.add(SearchHit(title = title, url = url, snippet = ""))
                }
            }
            return hits.distinctBy { it.url }
        }
        links.forEach { m ->
            val url = unwrapUrl(m.groupValues[1])
            val title = stripHtml(m.groupValues[2])
            if (url.startsWith("http") && title.isNotBlank()) {
                hits.add(SearchHit(title = title, url = url, snippet = ""))
            }
        }
        return hits.distinctBy { it.url }
    }

    /** Formats hits as a prompt-injection context block (numbered, with URLs). */
    fun contextBlock(query: String, hits: List<SearchHit>): String = buildString {
        appendLine("[Web results for \"$query\"]")
        hits.forEachIndexed { i, h ->
            appendLine("${i + 1}. ${h.title} — ${h.url}")
            if (h.snippet.isNotBlank()) appendLine("   ${h.snippet.take(300)}")
        }
        appendLine("Use these results when relevant; cite the URL in your answer.")
    }
}
