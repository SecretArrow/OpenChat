package com.openchat.android.ai

import com.openchat.android.core.model.Attachment
import com.openchat.android.core.model.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the attachment classifier and prompt builder (pure parts —
 * no Android framework involvement).
 */
class AttachmentsTest {

    @Test
    fun `mime drives the kind`() {
        assertEquals(AttachmentKind.IMAGE, Attachments.kindFor("x.png", "image/png"))
        assertEquals(AttachmentKind.TEXT, Attachments.kindFor("notes.txt", "text/plain"))
        assertEquals(AttachmentKind.TEXT, Attachments.kindFor("data.json", "application/json"))
        assertEquals(AttachmentKind.TEXT, Attachments.kindFor("main.kt", "application/octet-stream"))
        assertEquals(AttachmentKind.BINARY, Attachments.kindFor("blob.bin", "application/octet-stream"))
        assertEquals(AttachmentKind.BINARY, Attachments.kindFor("doc.pdf", "application/pdf"))
    }

    @Test
    fun `extension drives text kind for unknown mime`() {
        assertEquals(AttachmentKind.TEXT, Attachments.kindFor("script.py", ""))
        assertEquals(AttachmentKind.TEXT, Attachments.kindFor("Dockerfile", ""))
        assertEquals(AttachmentKind.BINARY, Attachments.kindFor("archive.zip", ""))
    }

    @Test
    fun `promptBlock fences text files and manifests binaries`() {
        val atts = listOf(
            Attachment("a.py", "text/x-python", 10, AttachmentKind.TEXT, textContent = "print(1)"),
            Attachment("img.png", "image/png", 2048, AttachmentKind.IMAGE, localPath = "/tmp/x"),
            Attachment("blob.bin", "application/octet-stream", 9, AttachmentKind.BINARY),
        )
        val block = Attachments.promptBlock(atts)
        assertTrue(block.contains("[Attached files]"))
        assertTrue(block.contains("```a.py"))
        assertTrue(block.contains("print(1)"))
        assertTrue(block.contains("[Attached files — content not inlined]"))
        assertTrue(block.contains("img.png"))
        assertTrue(block.contains("vision input"))
        assertTrue(block.contains("blob.bin"))
    }

    @Test
    fun `fence grows when content itself contains backticks`() {
        val atts = listOf(
            Attachment("a.md", "text/markdown", 12, AttachmentKind.TEXT, textContent = "```code```"),
        )
        val block = Attachments.promptBlock(atts)
        assertTrue(block.contains("````a.md"))
    }

    @Test
    fun `composeUserContent combines text and attachments`() {
        val atts = listOf(
            Attachment("a.txt", "text/plain", 3, AttachmentKind.TEXT, textContent = "abc"),
        )
        assertEquals("[Attached files]\n```a.txt\nabc\n```", Attachments.composeUserContent("", atts).trim())
        assertEquals(
            "Question\n\n[Attached files]\n```a.txt\nabc\n```",
            Attachments.composeUserContent("Question", atts),
        )
        assertEquals("Plain", Attachments.composeUserContent("Plain", emptyList()))
    }
}
