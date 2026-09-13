package com.openchat.android.ai.local

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.openchat.android.core.net.Http
import com.openchat.android.core.storage.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Per-model download lifecycle (spec §26: honest states, resumable). */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Running(val received: Long, val total: Long, val bytesPerSec: Long) : DownloadState()
    data class Paused(val received: Long, val total: Long) : DownloadState()
    object Done : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/**
 * Manager for on-device GGUF models (spec §10 model manager + §15/§16 file
 * lifecycle, applied to local inference):
 *  - curated catalog + imported models, persisted in JsonStore
 *  - resumable downloads (HTTP Range + .part/.meta sidecar, survives restarts)
 *  - pause / resume / cancel, import from phone (SAF), export to phone (SAF)
 *  - delete (with an unload hook so a loaded model is released first)
 *
 * The engine itself lives in [LocalInferenceEngine]; this class never loads
 * models into memory — it manages files and state only.
 */
class LocalModelManager(
    private val json: JsonStore,
    private val modelDir: File,
    private val resolver: ContentResolver? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val state = MutableStateFlow(loadSpecs())
    val models: StateFlow<List<LocalModelSpec>> = state

    private val dl = MutableStateFlow(scanDownloadStates())
    val downloads: StateFlow<Map<String, DownloadState>> = dl

    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** Set by AppGraph: unload the engine before the file disappears. */
    var onDeleteHook: ((LocalModelSpec) -> Unit)? = null

    init {
        modelDir.mkdirs()
    }

    // ------------------------------------------------------------------ queries

    fun spec(id: String): LocalModelSpec? = state.value.firstOrNull { it.id == id }

    fun specForAiId(aiId: String): LocalModelSpec? =
        aiId.removePrefix(LocalModelSpec.AI_PREFIX).let { spec(it) }
            .takeIf { aiId.startsWith(LocalModelSpec.AI_PREFIX) }

    fun fileFor(id: String): File = File(modelDir, "$id.gguf")

    fun isDownloaded(id: String): Boolean = fileFor(id).let { it.exists() && it.length() > 0L }

    fun partFile(id: String): File = File(modelDir, "$id.part")

    fun metaFile(id: String): File = File(modelDir, "$id.meta")

    // ------------------------------------------------------------------ actions

    /** Starts (or resumes) the download for [id]. No-op when already running. */
    fun download(id: String) {
        val s = spec(id) ?: return
        if (s.url == null) return
        if (!active.add(id)) return
        pauseFlags.getOrPut(id) { AtomicBoolean(false) }.set(false)
        cancelFlags.getOrPut(id) { AtomicBoolean(false) }.set(false)
        scope.launch { runDownload(s) }
    }

    fun pause(id: String) {
        pauseFlags.getOrPut(id) { AtomicBoolean(true) }.set(true)
    }

    /** Cancel deletes the partial file — the model returns to "not downloaded". */
    fun cancel(id: String) {
        val flag = cancelFlags.getOrPut(id) { AtomicBoolean(true) }
        if (active.contains(id)) {
            flag.set(true)
        } else {
            partFile(id).delete(); metaFile(id).delete()
            dl.update { it + (id to DownloadState.Idle) }
        }
    }

    fun delete(id: String) {
        val s = spec(id) ?: return
        onDeleteHook?.invoke(s)
        cancel(id)
        fileFor(id).delete()
        if (s.source == LocalModelSpec.SOURCE_IMPORTED) {
            state.update { list -> list.filterNot { it.id == id } }
            persist()
        } else {
            updateSpec(id) { it.copy(downloadedAt = null) }
        }
        dl.update { it + (id to DownloadState.Idle) }
    }

    /** Copies a user-provided GGUF stream into the model dir (testable core). */
    fun importFromStream(displayName: String, input: InputStream): Result<LocalModelSpec> = runCatching {
        val safeName = displayName.substringAfterLast('/').ifBlank { "model.gguf" }
        val base = safeName.removeSuffix(".gguf").ifBlank { "imported" }
        val id = "import-${System.currentTimeMillis()}-$base"
        val dst = fileFor(id)
        FileOutputStream(dst).use { out -> input.copyTo(out, 64 * 1024) }
        if (dst.length() == 0L) {
            dst.delete()
            error("Imported file is empty")
        }
        val spec = LocalModelSpec(
            id = id,
            name = base,
            repo = null,
            file = null,
            sizeBytes = dst.length(),
            params = "?",
            quant = guessQuant(base),
            notes = "Imported model file",
            source = LocalModelSpec.SOURCE_IMPORTED,
            downloadedAt = System.currentTimeMillis(),
        )
        state.update { it + spec }
        persist()
        dl.update { it + (id to DownloadState.Done) }
        spec
    }.onFailure {
        Log.w(TAG, "import failed: ${it.message}")
    }

    /** SAF import path (phone → app). Requires a resolver (Android runtime). */
    fun importFromUri(uri: Uri): Result<LocalModelSpec> {
        val r = resolver ?: return Result.failure(IllegalStateException("No ContentResolver"))
        val name = queryDisplayName(r, uri) ?: "imported.gguf"
        return r.openInputStream(uri).use { ins ->
            if (ins == null) return Result.failure(IllegalStateException("Cannot open the selected file"))
            importFromStream(name, ins)
        }
    }

    /** Copies a downloaded GGUF to an arbitrary stream (testable core). */
    fun exportToStream(id: String, out: OutputStream): Result<Unit> = runCatching {
        val f = fileFor(id)
        if (!f.exists()) error("Model file not found")
        f.inputStream().use { ins -> ins.copyTo(out, 64 * 1024) }
    }.onFailure {
        Log.w(TAG, "export failed: ${it.message}")
    }

    /** SAF export path (app → phone). Requires a resolver (Android runtime). */
    fun exportToUri(id: String, uri: Uri): Result<Unit> {
        val r = resolver ?: return Result.failure(IllegalStateException("No ContentResolver"))
        return r.openOutputStream(uri, "wt").use { out ->
            if (out == null) return Result.failure(IllegalStateException("Cannot open the destination"))
            exportToStream(id, out)
        }
    }

    /** Rebuilds download states from disk — used after process restarts. */
    fun refreshDownloadStates() {
        dl.value = scanDownloadStates()
    }

    /** Marks a model file that was placed externally as downloaded (tests/tools). */
    internal fun markDownloaded(id: String, at: Long = System.currentTimeMillis()) {
        updateSpec(id) { it.copy(downloadedAt = at) }
        dl.update { it + (id to DownloadState.Done) }
    }

    // ------------------------------------------------------------------ internals

    private fun scanDownloadStates(): Map<String, DownloadState> {
        val map = HashMap<String, DownloadState>()
        for (s in state.value) {
            if (isDownloaded(s.id)) map[s.id] = DownloadState.Done
        }
        val files = modelDir.listFiles() ?: return map
        for (f in files) {
            if (!f.name.endsWith(".part")) continue
            val id = f.name.removeSuffix(".part")
            if (isDownloaded(id)) continue
            val meta = metaFile(id)
            var total = spec(id)?.sizeBytes ?: 0L
            if (meta.exists()) {
                runCatching { total = JSONObject(meta.readText()).optLong("total", total) }
            }
            map[id] = DownloadState.Paused(f.length(), total)
        }
        return map
    }

    private fun runDownload(spec: LocalModelSpec) {
        val id = spec.id
        val url = spec.url ?: return
        val part = partFile(id)
        val meta = metaFile(id)
        val pause = pauseFlags.getOrPut(id) { AtomicBoolean(false) }
        val cancel = cancelFlags.getOrPut(id) { AtomicBoolean(false) }
        try {
            dl.update { it + (id to DownloadState.Idle) }
            var offset = if (part.exists()) part.length() else 0L
            var total = spec.sizeBytes
            if (meta.exists()) {
                runCatching {
                    val o = JSONObject(meta.readText())
                    total = o.optLong("total", total)
                }
            }
            if (offset > 0L && offset >= total && total > 0L) offset = 0L // corrupt part

            val builder = Request.Builder().url(url).header("User-Agent", "OpenChat-Android")
            if (offset > 0L) builder.header("Range", "bytes=$offset-")

            Http.client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    fail(id, "HTTP ${resp.code}"); return
                }
                val body = resp.body
                if (body == null) { fail(id, "empty response"); return }

                if (offset > 0L && resp.code != 206) {
                    offset = 0L // server ignored the range — start over
                }
                if (resp.code == 206) {
                    resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?.let { total = it }
                } else {
                    body.contentLength().takeIf { it > 0 }?.let { total = it.toLong() }
                }
                if (offset > 0L && offset > part.length()) offset = part.length()

                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(offset)
                    raf.seek(offset)
                    var received = offset
                    var lastUi = 0L
                    var lastBytes = 0L
                    var lastRate = 0L
                    body.byteStream().use { ins ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancel.get()) {
                                part.delete(); meta.delete()
                                dl.update { it + (id to DownloadState.Idle) }
                                return
                            }
                            if (pause.get()) {
                                writeMeta(meta, total, received)
                                dl.update { it + (id to DownloadState.Paused(received, total)) }
                                return
                            }
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            received += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 250) {
                                val dt = now - lastUi
                                if (dt > 0) lastRate = (received - lastBytes) * 1000 / dt
                                lastBytes = received; lastUi = now
                                dl.update { it + (id to DownloadState.Running(received, total, lastRate)) }
                            }
                        }
                    }
                    if (total > 0L && received < total) {
                        writeMeta(meta, total, received) // stream cut short → resumable
                        dl.update { it + (id to DownloadState.Paused(received, total)) }
                        return
                    }
                    if (!part.renameTo(fileFor(id))) {
                        part.copyTo(fileFor(id), overwrite = true)
                        part.delete()
                    }
                    meta.delete()
                    updateSpec(id) { it.copy(downloadedAt = System.currentTimeMillis()) }
                    dl.update { it + (id to DownloadState.Done) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: ${t.message}")
            val p = partFile(id)
            if (p.exists() && p.length() > 0L) {
                writeMeta(metaFile(id), spec.sizeBytes, p.length())
                dl.update { it + (id to DownloadState.Paused(p.length(), spec.sizeBytes)) }
            } else {
                dl.update { it + (id to DownloadState.Failed(t.message ?: "network error")) }
            }
        } finally {
            active.remove(id)
        }
    }

    private fun fail(id: String, message: String) {
        dl.update { it + (id to DownloadState.Failed(message)) }
    }

    private fun writeMeta(meta: File, total: Long, received: Long) {
        runCatching {
            meta.writeText(JSONObject().put("total", total).put("received", received).toString())
        }
    }

    private fun updateSpec(id: String, transform: (LocalModelSpec) -> LocalModelSpec) {
        state.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persist()
    }

    private fun loadSpecs(): List<LocalModelSpec> {
        val catalog = LocalCatalog.MODELS
        val stored = json.readText(FILE)?.let { text ->
            runCatching {
                val arr = JSONObject(text).optJSONArray("items") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { LocalModelSpec.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }.getOrNull().orEmpty()
        }.orEmpty()
        val imported = stored.filter { it.source == LocalModelSpec.SOURCE_IMPORTED }
        val storedById = stored.associateBy { it.id }
        // Catalog stays authoritative; persisted rows only contribute downloadedAt.
        val merged = catalog.map { c ->
            val d = storedById[c.id]?.downloadedAt
            if (d != null && c.downloadedAt == null) c.copy(downloadedAt = d) else c
        }
        // New catalog entries that appeared after an app update.
        val knownIds = merged.map { it.id }.toSet()
        val extraCatalog = stored.filter {
            it.source == LocalModelSpec.SOURCE_CATALOG && it.id !in knownIds
        }
        return merged + extraCatalog + imported
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            state.value.forEach { arr.put(it.toJson()) }
            json.writeText(FILE, JSONObject().put("items", arr).toString())
        }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun queryDisplayName(r: ContentResolver, uri: Uri): String? = runCatching {
        r.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun guessQuant(name: String): String {
        val m = Regex("(?i)(iq[0-9]_[a-z]+|q[0-9]_[a-z0-9_]+)").find(name)
        return m?.value?.uppercase() ?: "?"
    }

    companion object {
        private const val TAG = "OpenChat/LocalModels"
        const val FILE = "localmodels.json"
    }
}
