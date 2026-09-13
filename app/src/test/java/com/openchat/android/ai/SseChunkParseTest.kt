package com.openchat.android.ai

import com.openchat.android.ai.providers.Sse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SSE / streaming-delta parsing helpers in
 * [com.openchat.android.ai.providers.Sse] (INTEGRATION.md test contract:
 * `splitSseData` + `openAIDelta`). No Android classes, no network.
 */
class SseChunkParseTest {

    // ------------------------------------------------------------ splitSseData

    @Test
    fun splits_data_lines_from_a_raw_openai_style_chunk() {
        val raw = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}]}\n\n" +
            "data: [DONE]\n\n"
        val payloads = Sse.splitSseData(raw)
        assertEquals(3, payloads.size)
        assertTrue(payloads[0].contains("\"content\":\"Hi\""))
        assertTrue(payloads[1].contains("\"content\":\"!\""))
        assertEquals("[DONE]", payloads[2])
    }

    @Test
    fun blank_lines_event_lines_and_comments_are_ignored() {
        val raw = "\n" +
            ": keep-alive comment\n" +
            "event: content_block_delta\n" +
            "id: 42\n" +
            "retry: 3000\n" +
            "data: {\"type\":\"content_block_delta\"}\n" +
            "\n"
        val payloads = Sse.splitSseData(raw)
        assertEquals(1, payloads.size)
        assertEquals("{\"type\":\"content_block_delta\"}", payloads[0])
    }

    @Test
    fun crlf_line_endings_are_handled() {
        val raw = "event: message\r\ndata: {\"a\":1}\r\n\r\ndata: [DONE]\r\n"
        val payloads = Sse.splitSseData(raw)
        assertEquals(2, payloads.size)
        assertEquals("{\"a\":1}", payloads[0])
        assertEquals("[DONE]", payloads[1])
    }

    @Test
    fun data_without_a_space_after_the_colon_is_still_extracted() {
        val payloads = Sse.splitSseData("data:{\"x\":true}\n")
        assertEquals(1, payloads.size)
        assertEquals("{\"x\":true}", payloads[0])
    }

    @Test
    fun chunk_without_any_data_line_yields_nothing() {
        assertTrue(Sse.splitSseData("event: ping\n\n: noop\n").isEmpty())
        assertTrue(Sse.splitSseData("").isEmpty())
    }

    // ------------------------------------------------------------ openAIDelta

    @Test
    fun openai_delta_extracts_choices0_delta_content() {
        assertEquals(
            "Hello",
            Sse.openAIDelta("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"),
        )
    }

    @Test
    fun openai_delta_returns_null_for_done_sentinel_and_role_chunks() {
        assertNull(Sse.openAIDelta("[DONE]"))
        assertNull(Sse.openAIDelta("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"))
        assertNull(Sse.openAIDelta("{\"choices\":[{\"delta\":{\"content\":null}}]}"))
        assertNull(Sse.openAIDelta("{\"choices\":[]}"))
    }

    @Test
    fun openai_delta_returns_null_on_malformed_json() {
        assertNull(Sse.openAIDelta("not-json at all {"))
        assertNull(Sse.openAIDelta(""))
        assertNull(Sse.openAIDelta("{\"choices\":\"oops\"}"))
        assertNull(Sse.openAIDelta("{\"choices\":[{\"delta\":{\"content\":\"unterminated}"))
    }

    // ------------------------------- other provider payloads (stream sanity)

    @Test
    fun anthropic_delta_stop_and_error_payloads_parse() {
        assertEquals(
            " world",
            Sse.parseAnthropicDelta("{\"type\":\"content_block_delta\",\"delta\":{\"text\":\" world\"}}"),
        )
        assertTrue(Sse.parseAnthropicStop("{\"type\":\"message_stop\"}"))
        assertEquals(
            "overloaded",
            Sse.parseAnthropicError("{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}"),
        )
        assertNull(Sse.parseAnthropicDelta("{\"type\":\"message_start\"}"))
        assertNull(Sse.parseAnthropicDelta("garbage"))
    }

    @Test
    fun gemini_delta_and_error_payloads_parse() {
        assertEquals(
            "G",
            Sse.parseGeminiDelta("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"G\"}]}}]}"),
        )
        assertEquals(
            "bad key",
            Sse.parseGeminiError("{\"error\":{\"message\":\"bad key\"}}"),
        )
        assertNull(Sse.parseGeminiDelta("garbage"))
    }

    @Test
    fun ollama_chat_delta_done_and_error_lines_parse() {
        assertEquals(
            "Hi",
            Sse.parseOllamaChatDelta("{\"message\":{\"content\":\"Hi\"},\"done\":false}"),
        )
        assertTrue(Sse.parseOllamaDone("{\"done\":true}"))
        assertEquals(
            "model not found",
            Sse.parseOllamaError("{\"error\":\"model not found\"}"),
        )
        assertNull(Sse.parseOllamaChatDelta("{\"done\":true}"))
    }
}
