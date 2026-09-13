package com.openchat.android.ai

import com.openchat.android.ai.providers.ChatClients
import com.openchat.android.ai.providers.OllamaHttpClient
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SecretStore
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of AI providers (contract: ai/ProviderManager.kt).
 *
 * Provider metadata lives in `providers.json`; raw API keys NEVER do — they are
 * stored in the [SecretStore] under the reference `provider:<id>` (spec §12).
 */
class ProviderManager(private val secrets: SecretStore, private val json: JsonStore) {

    private val state = MutableStateFlow<List<Provider>>(load())
    val providers: StateFlow<List<Provider>> = state

    init {
        // Expose the latest instance so ModelManager(json) (constructor per contract)
        // can resolve providers for real test requests without a hard ctor dependency.
        sharedForModels = this
    }

    /** Persists a provider (insert or update). The API key is never stored here. */
    fun upsert(p: Provider) {
        val withId = if (p.id.isBlank()) p.copy(id = UUID.randomUUID().toString()) else p
        val fixed = if (withId.apiKeyRef == apiKeyRef(withId.id)) withId
        else withId.copy(apiKeyRef = apiKeyRef(withId.id))
        val next = state.value.filterNot { it.id == fixed.id } + fixed
        state.value = next.sortedBy { it.name.lowercase() }
        persist()
    }

    /** Removes a provider and its stored API key. */
    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        secrets.delete(apiKeyRef(id))
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    /** Reads the provider API key from the [SecretStore] (null when unset). */
    fun apiKeyFor(p: Provider): String? {
        val ref = p.apiKeyRef?.takeIf { it.isNotBlank() } ?: apiKeyRef(p.id)
        if (ref.isBlank()) return null
        return secrets.get(ref)
    }

    /**
     * Stores or clears the provider API key. `null`/blank deletes the stored key.
     * Returns true when the operation succeeded.
     */
    fun setApiKey(p: Provider, value: String?): Boolean {
        val id = p.id.ifBlank { return false }
        val ref = apiKeyRef(id)
        val ok = if (value.isNullOrBlank()) {
            secrets.delete(ref)
            true
        } else {
            secrets.put(ref, value.trim())
        }
        if (ok && state.value.none { it.id == id }) upsert(p.copy(id = id, apiKeyRef = ref))
        else if (ok) persist()
        return ok
    }

    /**
     * Real connectivity/auth probe. OLLAMA routes to [OllamaHttpClient.testConnection]
     * (no key required); everything else goes through [ChatClients.forProvider].
     * Failure messages never contain the API key.
     */
    suspend fun testConnection(p: Provider, apiKey: String?): Result<String> {
        val key = if (apiKey.isNullOrBlank()) apiKeyFor(p) else apiKey
        return try {
            when (p.type) {
                ProviderType.OLLAMA -> Result.success(OllamaHttpClient.testConnection(p.baseUrl, key))
                else -> ChatClients.forProvider(p.type).testConnection(p, key)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val pe = if (t is ProviderException) t else toSafeError(t, key)
            Result.failure(pe)
        }
    }

    // ------------------------------------------------------------------ impl

    private fun toSafeError(t: Throwable, vararg secrets: String?): ProviderException {
        val info = Errors.network(t, "Connection test")
        return ProviderException(info.copy(detail = Redact.scrub(info.detail, *secrets)))
    }

    private fun apiKeyRef(id: String): String = "provider:$id"

    private fun persist() {
        val arr = JSONArray()
        state.value.forEach { arr.put(it.toJson()) }
        json.writeText(FILE, JSONObject().put("items", arr).toString())
    }

    private fun load(): List<Provider> =
        runCatching {
            json.readText(FILE)?.let { text ->
                val arr = JSONObject(text).optJSONArray("items") ?: return@let emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { Provider.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }
        }.getOrNull().orEmpty()

    companion object {
        const val FILE = "providers.json"

        /** Latest instance, used by ModelManager's contract constructor (see init). */
        @Volatile
        internal var sharedForModels: ProviderManager? = null
    }
}
