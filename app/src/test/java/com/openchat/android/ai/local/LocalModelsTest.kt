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
            reasoning = true,
        )
        val back = LocalModelSpec.fromJson(s.toJson())
        assertEquals(s, back)
        assertNullCompat(back.repo)
        assertNullCompat(back.file)
        assertTrue(back.reasoning)
    }

    @Test
    fun `catalog includes the verified 2025 mobile wave`() {
        val ids = LocalCatalog.MODELS.map { it.id }.toSet()
        val expected = setOf(
            "qwen2.5-coder-0.5b-instruct-q4_k_m",
            "qwen3-0.6b-q4_k_m",
            "qwen3-1.7b-q4_k_m",
            "qwen3-4b-instruct-2507-q4_k_m",
            "qwen3-4b-thinking-2507-q4_k_m",
            "qwen2.5-3b-instruct-q4_k_m",
            "gemma-3-1b-it-q4_k_m",
            "gemma-3-4b-it-q4_k_m",
            "smollm2-360m-instruct-q8_0",
            "smollm3-3b-q4_k_m",
            "phi-4-mini-instruct-q4_k_m",
        )
        assertTrue("missing: ${expected - ids}", ids.containsAll(expected))
        assertTrue("expected ≥20 models, got ${ids.size}", ids.size >= 20)
    }

    @Test
    fun `reasoning flag is set exactly on thinking models`() {
        val reasoning = LocalCatalog.MODELS.filter { it.reasoning }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "smollm3-3b-q4_k_m",
                "qwen3-1.7b-q4_k_m",
                "qwen3-0.6b-q4_k_m",
                "qwen3-4b-thinking-2507-q4_k_m",
                "deepseek-r1-distill-qwen-1.5b-q4_k_m",
            ),
            reasoning,
        )
        // and the AIModel adapter carries it through
        val think = LocalCatalog.MODELS.first { it.id == "qwen3-4b-thinking-2507-q4_k_m" }
        assertTrue(think.toAIModel().reasoning)
        val plain = LocalCatalog.MODELS.first { it.id == "qwen3-4b-instruct-2507-q4_k_m" }
        assertFalse(plain.toAIModel().reasoning)
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
