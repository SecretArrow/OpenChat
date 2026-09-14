package com.openchat.android.core.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON round-trips for the Agent-mode model additions: attachments,
 * tool-block sources and the per-conversation webSearch switch — all must be
 * backward-compatible with documents saved by older builds.
 */
class ChatAgentModelsTest {

    @Test
    fun `attachment json roundtrip keeps kind and content`() {
        val a = Attachment(
            name = "notes.md",
            mime = "text/markdown",
            sizeBytes = 42,
            kind = AttachmentKind.TEXT,
            textContent = "# hello",
        )
        val back = Attachment.fromJson(a.toJson())
        assertEquals(a, back)
    }

    @Test
    fun `message json roundtrip keeps attachments and sources`() {
        val msg = ChatMessage(
            id = "m1",
            role = Role.USER,
            content = "hello",
            attachments = listOf(
                Attachment("img.png", "image/png", 1024, AttachmentKind.IMAGE, localPath = "/x/img.png"),
                Attachment("a.json", "application/json", 10, AttachmentKind.TEXT, textContent = "{}"),
            ),
            toolBlocks = listOf(
                ToolBlock(
                    id = "t1",
                    label = "Web search",
                    command = "q",
                    output = "1. T\n url",
                    sources = listOf("https://a.example", "https://b.example"),
                ),
            ),
        )
        val back = ChatMessage.fromJson(msg.toJson())
        assertEquals(msg.attachments, back.attachments)
        assertEquals(msg.toolBlocks.first().sources, back.toolBlocks.first().sources)
    }

    @Test
    fun `old documents without the new fields still load`() {
        val legacy = JSONObject()
            .put("id", "m1")
            .put("role", "USER")
            .put("content", "hi")
            .put("toolBlocks", JSONArray())
            .put("timestamp", 1L)
        val msg = ChatMessage.fromJson(legacy)
        assertEquals(0, msg.attachments.size)
        assertEquals("hi", msg.content)

        val legacyConv = JSONObject()
            .put("id", "c1")
            .put("title", "t")
            .put("messages", JSONArray())
        val conv = Conversation.fromJson(legacyConv)
        assertFalse(conv.webSearch)
        assertEquals(ChatBackend.DIRECT, conv.backend)
    }

    @Test
    fun `conversation webSearch roundtrip`() {
        val conv = Conversation(id = "c2", title = "x", webSearch = true)
        val back = Conversation.fromJson(conv.toJson())
        assertTrue(back.webSearch)
    }
}
