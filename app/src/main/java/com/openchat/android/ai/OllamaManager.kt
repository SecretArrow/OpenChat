package com.openchat.android.ai

import com.openchat.android.ai.providers.OllamaHttpClient
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.core.model.OllamaModel
import com.openchat.android.core.model.OllamaServer
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SecretStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager for Ollama servers — local AND remote are first-class (spec §11).
 *
 * State persists in `ollama.json` (`servers` + per-server `modelCache`); raw
 * server keys (when a server sits behind an authenticated proxy) live only in
 * the [SecretStore] via `apiKeyRef`.
 */
class OllamaManager(private val json: JsonStore, private val secrets: SecretStore) {

    private val loaded = load()

    private val serversState = MutableStateFlow(loaded.first)
    val servers: StateFlow<List<OllamaServer>> = serversState

    private val cacheState = MutableStateFlow(loaded.second)
    val modelCache: StateFlow<Map<String, List<OllamaModel>>> = cacheState

    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState

    fun upsertServer(s: OllamaServer) {
        val withId = if (s.id.isBlank()) s.copy(id = UUID.randomUUID().toString()) else s
        serversState.value = serversState.value.filterNot { it.id == withId.id } + withId
        persist()
    }

    fun removeServer(id: String) {
        serversState.value = serversState.value.filterNot { it.id == id }
        s(id)?.apiKeyRef?.let { secrets.delete(it) }
        cacheState.value = cacheState.value - id
        persist()
    }

    /** GET /api/tags connectivity probe. */
    suspend fun testServer(s: OllamaServer): Result<String> = guarded(s.baseUrl) {
        val key = apiKeyFor(s)
        Result.success(OllamaHttpClient.testConnection(s.baseUrl, key))
    }

    /** GET /api/tags → refreshes the per-server model cache and returns the list. */
    suspend fun refreshModels(s: OllamaServer): Result<List<OllamaModel>> = guarded(s.baseUrl) {
        val key = apiKeyFor(s)
        val models = OllamaHttpClient.listTags(s.baseUrl, key)
        cacheState.value = cacheState.value + (s.id to models)
        persist()
        Result.success(models)
    }

    /** POST /api/pull (streaming) — progress like "pulling llama3.1:8b: 45%". */
    suspend fun pullModel(s: OllamaServer, name: String, onProgress: (String) -> Unit): Result<Unit> = guarded(s.baseUrl) {
        val key = apiKeyFor(s)
        OllamaHttpClient.pull(s.baseUrl, name, onProgress, key)
        Result.success(Unit)
    }

    /** DELETE /api/delete and drop the model from the cache. */
    suspend fun deleteModel(s: OllamaServer, name: String): Result<Unit> = guarded(s.baseUrl) {
        val key = apiKeyFor(s)
        OllamaHttpClient.deleteModel(s.baseUrl, name, key)
        cacheState.value = cacheState.value +
            (s.id to (cacheState.value[s.id].orEmpty().filterNot { it.name == name }))
        persist()
        Result.success(Unit)
    }

    /** POST /api/generate with an empty prompt — loads the model into memory. */
    suspend fun runModel(s: OllamaServer, name: String): Result<String> = guarded(s.baseUrl) {
        val key = apiKeyFor(s)
        Result.success(OllamaHttpClient.runLoaded(s.baseUrl, name, key))
    }

    /** Reads the server's optional API key from the [SecretStore]. */
    fun apiKeyFor(s: OllamaServer): String? =
        s.apiKeyRef?.takeIf { it.isNotBlank() }?.let { secrets.get(it) }

    // ------------------------------------------------------------------ impl

    private fun s(id: String): OllamaServer? = serversState.value.firstOrNull { it.id == id }

    /** Wraps transport failures into Result with honest, scrubbed error info. */
    private inline fun <T> guarded(baseUrl: String, block: () -> Result<T>): Result<T> {
        busyState.value = true
        return try {
            try {
                block()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(OllamaHttpClient.toOllamaError(t, baseUrl))
            }
        } finally {
            busyState.value = false
        }
    }

    private fun persist() {
        val serversArr = JSONArray()
        serversState.value.forEach { serversArr.put(it.toJson()) }
        val cacheObj = JSONObject()
        cacheState.value.forEach { (serverId, models) ->
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("name", m.name)
                        .put("size", m.sizeBytes)
                        .put("digest", m.digest ?: JSONObject.NULL)
                        .put("modified_at", m.modifiedAt ?: JSONObject.NULL)
                )
            }
            cacheObj.put(serverId, arr)
        }
        json.writeText(FILE, JSONObject().put("servers", serversArr).put("modelCache", cacheObj).toString())
    }

    private fun load(): Pair<List<OllamaServer>, Map<String, List<OllamaModel>>> =
        runCatching {
            val text = json.readText(FILE)
                ?: return@runCatching Pair(
                    emptyList<OllamaServer>(),
                    emptyMap<String, List<OllamaModel>>(),
                )
            val root = JSONObject(text)
            val servers = root.optJSONArray("servers")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { OllamaServer.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }.orEmpty()
            val cache = HashMap<String, List<OllamaModel>>()
            root.optJSONObject("modelCache")?.let { obj ->
                obj.keys().forEach { sid ->
                    val arr = obj.optJSONArray(sid) ?: return@forEach
                    cache[sid] = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        OllamaModel(
                            name = o.optString("name"),
                            sizeBytes = o.optLong("size", 0L),
                            digest = if (o.isNull("digest")) null else o.optString("digest"),
                            modifiedAt = if (o.isNull("modified_at")) null else o.optString("modified_at"),
                        )
                    }
                }
            }
            Pair(servers, cache)
        }.getOrNull() ?: Pair(emptyList(), emptyMap())

    companion object {
        const val FILE = "ollama.json"
    }
}
