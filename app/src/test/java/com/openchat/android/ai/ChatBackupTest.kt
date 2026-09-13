package com.openchat.android.ai

import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip + guard tests for the chat backup document (Settings → Backup). */
class ChatBackupTest {

    private fun sample(): List<Conversation> {
        val a = Conversation(
            id = "conv-a",
            title = "First",
            modelId = "m1",
            backend = com.openchat.android.core.model.ChatBackend.DIRECT,
        ).apply {
            messages.add(ChatMessage(id = "ma1", role = com.openchat.android.core.model.Role.USER, content = "Hello"))
            messages.add(
                ChatMessage(
                    id = "ma2",
                    role = com.openchat.android.core.model.Role.ASSISTANT,
                    content = "Hi!",
                    modelId = "m1",
                ),
            )
        }
        val b = Conversation(id = "conv-b", title = "Second")
        return listOf(a, b)
    }

    @Test
    fun `export then parse round trip preserves conversations`() {
        val json = ChatBackup.toJson(sample()).toString()
        val back = ChatBackup.parse(json).getOrThrow()
        assertEquals(2, back.size)
        val a = back.first { it.id == "conv-a" }
        assertEquals("First", a.title)
        assertEquals(2, a.messages.size)
        assertEquals("Hello", a.messages[0].content)
        assertEquals("Hi!", a.messages[1].content)
        assertEquals("m1", a.messages[1].modelId)
    }

    @Test
    fun `envelope has format marker and count`() {
        val root = ChatBackup.toJson(sample())
        assertEquals(ChatBackup.FORMAT, root.getString("format"))
        assertEquals(2, root.getInt("count"))
    }

    @Test
    fun `foreign json is rejected`() {
        val r = ChatBackup.parse("""{"format":"other","conversations":[]}""")
        assertTrue(r.isFailure)
        val r2 = ChatBackup.parse("not json at all")
        assertTrue(r2.isFailure)
    }

    @Test
    fun `undecodable entries are skipped not fatal`() {
        val json = """{"format":"${ChatBackup.FORMAT}","version":1,"conversations":[
            {"id":"ok-1","title":"Ok"},
            {"bogus":true},
            {"id":"","title":"blank id dropped"}
        ]}"""
        val back = ChatBackup.parse(json).getOrThrow()
        assertEquals(listOf("ok-1"), back.map { it.id })
    }
}
