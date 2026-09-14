package com.openchat.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ModelDiscovery.parseModelsResponse] against the wire shapes real
 * providers return: OpenAI `data[]`, Anthropic `data[]` with display names,
 * generic `models[]`, bare arrays, and hostile input (must never throw).
 */
class ModelDiscoveryParseTest {

    @Test
    fun `openai list format parses ids`() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-4o","object":"model","owned_by":"openai"},
              {"id":"gpt-4o-mini","object":"model","owned_by":"openai"},
              {"id":"o3-mini","object":"model","owned_by":"openai"}
            ]}
        """.trimIndent()
        assertEquals(listOf("gpt-4o", "gpt-4o-mini", "o3-mini"), ModelDiscovery.parseModelsResponse(body))
    }

    @Test
    fun `anthropic list format parses ids`() {
        val body = """
            {"data":[
              {"type":"model","id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"},
              {"type":"model","id":"claude-opus-4-1","display_name":"Claude Opus 4.1"}
            ],"has_more":false}
        """.trimIndent()
        assertEquals(
            listOf("claude-opus-4-1", "claude-sonnet-4-5"),
            ModelDiscovery.parseModelsResponse(body),
        )
    }

    @Test
    fun `generic models array parses id-name-and-model keys`() {
        assertEquals(
            listOf("m1", "m2", "m3"),
            ModelDiscovery.parseModelsResponse(
                """{"models":[{"id":"m1"},{"name":"m2"},{"model":"m3"}]}"""
            ),
        )
    }

    @Test
    fun `bare arrays parse strings and objects`() {
        assertEquals(listOf("a", "b", "c"), ModelDiscovery.parseModelsResponse("""["a",{"id":"b"},{"name":"c"}]"""))
    }

    @Test
    fun `single object descriptor parses its first id-like key`() {
        assertEquals(listOf("solo"), ModelDiscovery.parseModelsResponse("""{"id":"solo"}"""))
        assertEquals(listOf("solo"), ModelDiscovery.parseModelsResponse("""{"model":"solo"}"""))
    }

    @Test
    fun `output is deduplicated and case-insensitively sorted`() {
        val out = ModelDiscovery.parseModelsResponse(
            """{"data":[{"id":"zeta"},{"id":"Alpha"},{"id":"zeta"},{"id":"MID"}]}"""
        )
        assertEquals(listOf("Alpha", "MID", "zeta"), out)
    }

    @Test
    fun `hostile input never throws`() {
        assertTrue(ModelDiscovery.parseModelsResponse("").isEmpty())
        assertTrue(ModelDiscovery.parseModelsResponse("not json").isEmpty())
        assertTrue(ModelDiscovery.parseModelsResponse("""{"data":"not-an-array"}""").isEmpty())
        assertTrue(ModelDiscovery.parseModelsResponse("""{"data":[{"unrelated":1}]}""").isEmpty())
        assertTrue(ModelDiscovery.parseModelsResponse("""[42,null,{}]""").isEmpty())
    }

    @Test
    fun `blank ids are dropped`() {
        assertEquals(emptyList<String>(), ModelDiscovery.parseModelsResponse("""{"data":[{"id":""},{"id":"  "}]}"""))
    }
}
