package com.openchat.android.ai

import com.openchat.android.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Web search for the Agent composer — keyless, provider-agnostic.
 *
 * Uses DuckDuckGo's HTML endpoints (html.duckduckgo.com, fallback
 * lite.duckduckgo.com); the response HTML is parsed by the pure
 * [WebSearchParser]. Every failure is returned as a Result so the chat flow
 * can degrade gracefully (proceed without context) instead of crashing.
 */
object WebSearch {

    const val MAX_HITS = 5

    private const val HTML_ENDPOINT = "https://html.duckduckgo.com/html/?q="
    private const val LITE_ENDPOINT = "https://lite.duckduckgo.com/lite/?q="
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /**
     * Runs the search for [query]. Tries the HTML endpoint first, then the lite
     * endpoint. Never throws — failures come back as Result.failure.
     */
    suspend fun search(query: String): Result<List<SearchHit>> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        try {
            val html = fetch(HTML_ENDPOINT + q)
            val parsed = WebSearchParser.parseHtml(html)
            if (parsed.isNotEmpty()) return@withContext Result.success(parsed.take(MAX_HITS))
            val lite = fetch(LITE_ENDPOINT + q)
            val parsedLite = WebSearchParser.parseLite(lite)
            if (parsedLite.isNotEmpty()) return@withContext Result.success(parsedLite.take(MAX_HITS))
            Result.failure(IllegalStateException("the search returned no results for this query"))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        }
    }

    /** JSON-ready list of hit URLs (persistence helper for tool blocks). */
    fun hitsToSources(hits: List<SearchHit>): List<String> = hits.map { it.url }

    private fun fetch(url: String): String {
        val request = Http.newRequest(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} from search")
            return response.body?.string().orEmpty()
        }
    }
}
