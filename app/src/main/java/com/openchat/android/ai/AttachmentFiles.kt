package com.openchat.android.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openchat.android.core.model.Attachment
import com.openchat.android.core.model.AttachmentKind
import java.io.File

/**
 * Attachment file plumbing (Android-only): SAF uri → validated [Attachment].
 * Text-like files are read into memory (bounded), images are copied into the
 * app's private attachments dir (base64-encoded at send time), everything
 * else is attached by name/size only. Every failure is reported honestly in
 * the result instead of crashing the composer. Classification and prompt
 * rendering live in the pure [Attachments] rules object.
 */
object AttachmentFiles {

    /**
     * Reads [uri] (SAF) into an [Attachment]. Returns an error result for
     * unreadable/oversized/empty files — the composer shows it as a toast and
     * keeps working.
     */
    fun fromUri(context: Context, uri: Uri): Result<Attachment> = runCatching {
        val resolver = context.contentResolver
        val name = queryDisplayName(resolver, uri) ?: "file-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val size = querySize(resolver, uri)
        val kind = Attachments.kindFor(name, mime)

        when (kind) {
            AttachmentKind.IMAGE -> {
                if (size > Attachments.MAX_IMAGE_BYTES) {
                    error("image '$name' is larger than ${Attachments.MAX_IMAGE_BYTES / (1024 * 1024)} MB — compress or pick a smaller file")
                }
                val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image" }
                val target = File(dir, "${System.currentTimeMillis()}-$safe")
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: error("could not open '$name'")
                Attachment(
                    name = name,
                    mime = mime.ifBlank { "image/png" },
                    sizeBytes = target.length(),
                    kind = AttachmentKind.IMAGE,
                    localPath = target.absolutePath,
                )
            }
            AttachmentKind.TEXT -> {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("could not open '$name'")
                if (bytes.size > Attachments.MAX_TEXT_BYTES) {
                    error(
                        "text file '$name' is ${bytes.size / 1024} KB — over the ${Attachments.MAX_TEXT_BYTES / 1024} KB " +
                            "in-prompt limit; trim it or paste the relevant part",
                    )
                }
                val content = bytes.toString(Charsets.UTF_8)
                Attachment(
                    name = name,
                    mime = mime.ifBlank { "text/plain" },
                    sizeBytes = bytes.size.toLong(),
                    kind = AttachmentKind.TEXT,
                    textContent = content,
                )
            }
            AttachmentKind.BINARY -> Attachment(
                name = name,
                mime = mime,
                sizeBytes = size,
                kind = AttachmentKind.BINARY,
            )
        }
    }

    /**
     * Base64 (no line wraps) of an IMAGE attachment's local file — used by the
     * vision-capable transports. Null when the file went missing (the
     * transports skip that image instead of failing the whole request).
     */
    fun imageBase64(a: Attachment): String? {
        if (a.kind != AttachmentKind.IMAGE) return null
        val path = a.localPath ?: return null
        return runCatching {
            android.util.Base64.encodeToString(File(path).readBytes(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    /** IMAGE attachments with a readable file (transports iterate over these). */
    fun imagesOf(attachments: List<Attachment>): List<Attachment> =
        attachments.filter { imageBase64(it) != null }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrNull() ?: 0L
}
