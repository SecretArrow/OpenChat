package com.openchat.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the keyless DuckDuckGo result parsers. */
class WebSearchParserTest {

    private val htmlFixture = """
        <html><body>
        <div class="result results_links">
        <h2 class="result__title">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fguide&amp;rut=abc">Example <b>Guide</b> &amp; Tips</a>
        </h2>
        <a class="result__snippet" href="#">The first &amp; best snippet.</a>
        </div>
        <div class="result results_links">
        <h2 class="result__title">
          <a rel="nofollow" class="result__a" href="https://direct.example.org/page">Direct Link</a>
        </h2>
        <a class="result__snippet" href="#">Second snippet</a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `parseHtml extracts titles urls and snippets`() {
        val hits = WebSearchParser.parseHtml(htmlFixture)
        assertEquals(2, hits.size)
        assertEquals("https://example.com/guide", hits[0].url)
        assertEquals("Example Guide & Tips", hits[0].title)
        assertEquals("The first & best snippet.", hits[0].snippet)
        assertEquals("https://direct.example.org/page", hits[1].url)
    }

    @Test
    fun `unwrapUrl decodes uddg redirect and passes direct links`() {
        assertEquals(
            "https://example.com/a b?q=1&x=2",
            WebSearchParser.unwrapUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa+b%3Fq%3D1%26x%3D2&rut=x"),
        )
        assertEquals("https://example.com/plain", WebSearchParser.unwrapUrl("https://example.com/plain"))
    }

    @Test
    fun `stripHtml removes tags and entities`() {
        assertEquals("A & B \"quoted\" <tag>", WebSearchParser.stripHtml("<p>A &amp; B &quot;quoted&quot; &lt;tag&gt;</p>"))
    }

    @Test
    fun `parseLite reads result-link anchors and dedupes`() {
        val lite = """
            <table><tr>
              <td><a rel="nofollow" href="https://a.example/1" class="result-link">First result</a></td>
              <td class="result-snippet">Snip one</td>
            </tr><tr>
              <td><a rel="nofollow" href="https://a.example/1" class="result-link">First result</a></td>
            </tr></table>
        """.trimIndent()
        val hits = WebSearchParser.parseLite(lite)
        assertEquals(1, hits.size)
        assertEquals("https://a.example/1", hits[0].url)
    }

    @Test
    fun `contextBlock lists numbered hits with urls`() {
        val block = WebSearchParser.contextBlock(
            "test query",
            listOf(SearchHit("T1", "https://u1", "s1"), SearchHit("T2", "https://u2", "")),
        )
        assertTrue(block.contains("[Web results for \"test query\"]"))
        assertTrue(block.contains("1. T1 — https://u1"))
        assertTrue(block.contains("2. T2 — https://u2"))
        assertTrue(block.contains("cite the URL"))
    }
}
