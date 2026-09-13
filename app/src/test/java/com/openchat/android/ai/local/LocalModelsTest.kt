package com.openchat.android.ai.local

import com.openchat.android.core.storage.JsonStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** JVM tests for the on-device model layer (catalog, trim, file lifecycle). */
class LocalModelsTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "oc-local-${System.nanoTime()}").apply { mkdirs() }

    // ------------------------------------------------------------- catalog

    @Test
    fun `catalog entries are unique and verifiable`() {
        val ids = LocalCatalog.MODELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (m in LocalCatalog.MODELS) {
            assertEquals(LocalModelSpec.SOURCE_CATALOG, m.source)
            assertTrue("url must be https HF resolve: ${m.url}", m.url!!.startsWith("https://huggingface.co/"))
            assertTrue("url must point at a gguf file: ${m.url}", m.url!!.endsWith(".gguf"))
            assertTrue("size must be sane: ${m.id}", m.sizeBytes > 100L * 1024 * 1024)
            assertTrue(m.quant.isNotBlank())
            assertTrue(m.notes.isNotBlank())
            assertTrue(m.ramHintMb > m.sizeBytes / (1024 * 1024))
        }
    }

    @Test
    fun `spec json round trip preserves all fields`() {
        val s = LocalModelSpec(
            id = "x", name = "Model X", repo = null, file = null, sizeBytes = 123L,
            params = "1B", quant = "Q4_K_M", notes = "n",
            source = LocalModelSpec.SOURCE_IMPORTED, downloadedAt = 42L,
        )
        val back = LocalModelSpec.fromJson(s.toJson())
        assertEquals(s, back)
        assertNullCompat(back.repo)
        assertNullCompat(back.file)
    }

    private fun assertNullCompat(v: Any?) = assertTrue(v == null)

    // ---------------------------------------------------------------- trim

    @Test
    fun `trim passes small history through unchanged`() {
        val h = listOf("user" to "hi", "assistant" to "hello")
        assertEquals(h, LocalHistory.trim(h))
    }

    @Test
    fun `trim keeps newest and system within budget`() {
        val h = listOf(
            "system" to "sys",
            "user" to "x".repeat(20_000),      // old, must be dropped
            "assistant" to "y".repeat(100),
            "user" to "final question",        // newest, must survive
        )
        val out = LocalHistory.trim(h)
        assertEquals("final question", out.last().second)
        assertEquals("sys", out.first().second)
        assertTrue(out.sumOf { it.second.length } <= LocalHistory.CHAR_BUDGET + 10)
        assertTrue(out.none { it.second.length > 15_000 })
        assertEquals("assistant", out[out.size - 2].first)
    }

    // --------------------------------------------------------------- ai id

    @Test
    fun `ai model id prefix mapping`() {
        assertEquals("local:abc", LocalModelSpec.aiId("abc"))
        assertTrue(LocalCatalog.MODELS[0].aiModelId.startsWith(LocalModelSpec.AI_PREFIX))
        LocalCatalog.MODELS[0].toAIModel().let { m ->
            assertEquals("local", m.providerId)
            assertTrue(m.id.startsWith(LocalModelSpec.AI_PREFIX))
            assertTrue(m.displayName.isNotBlank())
        }
    }

    // ------------------------------------------------------- file lifecycle

    @Test
    fun `import export delete lifecycle works on plain files`() {
        val base = tempDir()
        val json = JsonStore(base)
        val dir = File(base, "models")
        val mgr = LocalModelManager(json, dir)

        // catalog is always present
        assertTrue(mgr.models.value.any { it.source == LocalModelSpec.SOURCE_CATALOG })

        val data = "GGUF-fake-bytes-123".toByteArray()
        val spec = mgr.importFromStream("my-model.GGUF", ByteArrayInputStream(data)).getOrThrow()
        assertEquals(LocalModelSpec.SOURCE_IMPORTED, spec.source)
        assertTrue(mgr.isDownloaded(spec.id))
        assertEquals(DownloadState.Done, mgr.downloads.value[spec.id])

        val out = ByteArrayOutputStream()
        mgr.exportToStream(spec.id, out).getOrThrow()
        assertEquals(data.toList(), out.toByteArray().toList())

        mgr.delete(spec.id)
        assertFalse(mgr.isDownloaded(spec.id))
        assertTrue(mgr.models.value.none { it.id == spec.id })

        // import with empty content fails honestly
        val bad = mgr.importFromStream("empty.gguf", ByteArrayInputStream(ByteArray(0)))
        assertTrue(bad.isFailure)
    }

    @Test
    fun `partial part file surfaces as resumable paused state`() {
        val base = tempDir()
        val json = JsonStore(base)
        val dir = File(base, "models").apply { mkdirs() }
        val catalogId = LocalCatalog.MODELS[0].id

        // simulate a leftover partial download from a previous process
        File(dir, "$catalogId.part").writeBytes(ByteArray(4096))

        val mgr = LocalModelManager(json, dir)
        mgr.refreshDownloadStates()
        val st = mgr.downloads.value[catalogId]
        assertTrue("expected Paused, got $st", st is DownloadState.Paused)
        assertEquals(4096L, (st as DownloadState.Paused).received)
        assertTrue(st.total > 0L)
        assertFalse(mgr.isDownloaded(catalogId))
    }

    @Test
    fun `downloaded catalog state is restored across manager restarts`() {
        val base = tempDir()
        val json = JsonStore(base)
        val dir = File(base, "models")
        val id = LocalCatalog.MODELS[0].id

        val mgr = LocalModelManager(json, dir)
        // simulate a completed download (file present, no metadata update path)
        val f = mgr.fileFor(id)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(1024))
        mgr.markDownloaded(id)

        val mgr2 = LocalModelManager(json, dir)
        mgr2.refreshDownloadStates()
        assertEquals(DownloadState.Done, mgr2.downloads.value[id])
        assertTrue(mgr2.models.value.first { it.id == id }.downloadedAt != null)
    }
}
