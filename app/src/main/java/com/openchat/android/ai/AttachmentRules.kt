package com.openchat.android.ai

import com.openchat.android.core.model.Attachment
import com.openchat.android.core.model.AttachmentKind

/**
 * Pure attachment rules — classification and prompt-side rendering. No
 * Android imports, JVM-tested ([AttachmentsTest]). File/uri plumbing lives
 * in [AttachmentFiles] (Android-only).
 */
object Attachments {

    /** Hard caps — protect RAM and provider payloads. */
    const val MAX_TEXT_BYTES = 128L * 1024L          // per text file, read into the prompt
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L   // per image file (base64 at send)
    const val MAX_TOTAL_TEXT = 256L * 1024L          // sum of all text content per message

    /** Extensions/mime prefixes treated as readable text. */
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yml", "yaml",
        "kt", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp",
        "go", "rs", "rb", "php", "sh", "bash", "zsh", "sql", "html", "css",
        "toml", "ini", "cfg", "conf", "properties", "gradle", "kts", "log",
        "dockerfile", "gitignore", "env", "swift", "dart", "lua", "pl",
    )

    private val IMAGE_PREFIXES = listOf("image/")

    /** Extensionless files that are plain text by convention. */
    private val TEXT_NAMES = setOf("dockerfile", "makefile", "license", "readme")

    /** Classifies a document by its display name + mime. */
    fun kindFor(name: String, mime: String): AttachmentKind = when {
        IMAGE_PREFIXES.any { mime.startsWith(it) } -> AttachmentKind.IMAGE
        mime.startsWith("text/") -> AttachmentKind.TEXT
        mime in setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/toml", "application/x-sh",
        ) -> AttachmentKind.TEXT
        else -> {
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.lowercase()
            if (ext in TEXT_EXT || base in TEXT_NAMES) AttachmentKind.TEXT else AttachmentKind.BINARY
        }
    }

    /**
     * Builds the prompt-side representation of a user message's attachments:
     * text content as fenced blocks + a name/size manifest for images and
     * binaries. Pure — JVM-tested.
     */
    fun promptBlock(attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return ""
        val out = StringBuilder()
        val texts = attachments.filter { it.kind == AttachmentKind.TEXT && !it.textContent.isNullOrBlank() }
        if (texts.isNotEmpty()) {
            out.appendLine("[Attached files]")
            texts.forEach { a ->
                val fence = if (a.textContent.orEmpty().contains("```")) "````" else "```"
                out.appendLine("${fence}${a.name}")
                out.appendLine(a.textContent)
                out.appendLine(fence)
            }
        }
        val others = attachments.filter { it.kind != AttachmentKind.TEXT }
        if (others.isNotEmpty()) {
            out.appendLine("[Attached files — content not inlined]")
            others.forEach { a ->
                val label = when (a.kind) {
                    AttachmentKind.IMAGE -> "image, sent to the model as vision input"
                    else -> "binary file (content not sent)"
                }
                out.appendLine("- ${a.name} (${a.mime}, ${a.sizeBytes} B — $label)")
            }
        }
        return out.toString().trimEnd()
    }

    /** Appends [promptBlock] to the user text with a blank-line separator. */
    fun composeUserContent(text: String, attachments: List<Attachment>): String {
        val block = promptBlock(attachments)
        return when {
            block.isEmpty() -> text
            text.isBlank() -> block
            else -> "$text\n\n$block"
        }
    }
}
