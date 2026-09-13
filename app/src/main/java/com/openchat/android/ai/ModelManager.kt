package com.openchat.android.ai

import com.openchat.android.ai.providers.ChatClients
import com.openchat.android.ai.providers.ChatRequest
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.ai.providers.toProviderError
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.model.Role
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of chat models (contract: ai/ModelManager.kt).
 *
 * Models live in `models.json` as `{"items":[...], "defaultModelId": ...}`.
 * `testModel` performs a tiny REAL request ("ping", maxTokens=16) through
 * [ChatClients] — no simulated responses.
 *
 * Note: the contract constructor is `ModelManager(json)`. To resolve the provider
 * and API key for real test requests it resolves the app-wide [ProviderManager]
 * lazily — either the one passed as the optional second constructor argument
 * (integrator wiring) or the latest instance published by [ProviderManager] in
 * its `init` (works regardless of construction order).
 */
class ModelManager(private val json: JsonStore, providers: ProviderManager? = null) {

    /** Explicitly wired provider registry (null when constructed per contract ctor). */
    private val explicitProviders: ProviderManager? = providers

    private val state = MutableStateFlow<List<AIModel>>(loadItems())
    val models: StateFlow<List<AIModel>> = state

    private val defaultState = MutableStateFlow<String?>(loadDefault())
    val defaultModelId: StateFlow<String?> = defaultState

    fun upsert(m: AIModel) {
        val withId = if (m.id.isBlank()) m.copy(id = UUID.randomUUID().toString()) else m
        state.value = state.value.filterNot { it.id == withId.id } + withId
        persist()
    }

    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
        if (defaultState.value == id) defaultState.value = null
        persist()
    }

    /** Duplicates a model under a new id ("… (copy)"); enabled by default. */
    fun duplicate(id: String): Result<AIModel> {
        val src = state.value.firstOrNull { it.id == id }
            ?: return Result.failure(
                ProviderException(
                    ErrorInfo(
                        title = "Model not found",
                        detail = "No model with id ${Redact.idOf(id)} exists.",
                        causes = listOf("The model was deleted elsewhere"),
                        suggestions = listOf("Reload the Models screen"),
                        retryable = false,
                    )
                )
            )
        val copy = src.copy(
            id = UUID.randomUUID().toString(),
            displayName = src.displayName + " (copy)",
            enabled = true,
        )
        upsert(copy)
        return Result.success(copy)
    }

    fun setDefault(id: String) {
        defaultState.value = id
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    /** The configured default model; falls back to the first enabled model. */
    fun defaultModel(): AIModel? {
        val byId = defaultState.value?.let { id -> state.value.firstOrNull { it.id == id } }
        return byId ?: state.value.firstOrNull { it.enabled }
    }

    fun byProvider(providerId: String): List<AIModel> =
        state.value.filter { it.providerId == providerId }

    /**
     * Tiny real request ("ping", maxTokens=16) against the model's provider to
     * verify endpoint + key + model name. Returns the response snippet on success.
     */
    suspend fun testModel(m: AIModel): Result<String> {
        // Resolved lazily so construction order (providers vs models first) never matters.
        val pm = explicitProviders ?: ProviderManager.sharedForModels
            ?: return Result.failure(
                ProviderException(
                    ErrorInfo(
                        title = "Provider registry unavailable",
                        detail = "ModelManager is not wired to a ProviderManager; cannot resolve the model's provider.",
                        causes = listOf("AppGraph wiring incomplete"),
                        suggestions = listOf("Pass a ProviderManager to ModelManager or create ProviderManager first"),
                        retryable = false,
                    )
                )
            )
        val provider = pm.providers.value.firstOrNull { it.id == m.providerId }
            ?: return Result.failure(
                ProviderException(
                    ErrorInfo(
                        title = "Provider not found",
                        detail = "Model '${m.displayName}' references provider '${m.providerId}' which no longer exists.",
                        causes = listOf("The provider was deleted", "Wrong providerId in the model"),
                        suggestions = listOf("Edit the model in Settings → Models and pick an existing provider"),
                        retryable = false,
                    )
                )
            )
        val key = pm.apiKeyFor(provider).orEmpty()
        if (key.isBlank() && provider.type != ProviderType.OLLAMA) {
            return Result.failure(ProviderException(Errors.providerAuth(provider.name)))
        }
        val request = ChatRequest(
            model = m,
            provider = provider,
            apiKey = key,
            messages = listOf(
                ChatMessage(id = UUID.randomUUID().toString(), role = Role.USER, content = "ping")
            ),
            maxTokens = 16,
            temperature = m.temperature,
        )
        return try {
            val buffer = StringBuilder()
            ChatClients.forProvider(provider.type).stream(request) { delta -> buffer.append(delta) }
                .fold(
                    onSuccess = { full ->
                        val text = full.ifBlank { buffer.toString() }
                        Result.success(
                            if (text.isBlank()) "OK — model responded"
                            else "OK — response: " + text.take(80)
                        )
                    },
                    onFailure = { Result.failure(it) },
                )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(toProviderError(t, "Model test", key))
        }
    }

    // ------------------------------------------------------------------ impl

    private fun persist() {
        val arr = JSONArray()
        state.value.forEach { arr.put(it.toJson()) }
        json.writeText(
            FILE,
            JSONObject().put("items", arr).put("defaultModelId", defaultState.value ?: JSONObject.NULL).toString()
        )
    }

    private fun loadItems(): List<AIModel> =
        runCatching {
            json.readText(FILE)?.let { text ->
                val arr = JSONObject(text).optJSONArray("items") ?: return@let emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { AIModel.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }
        }.getOrNull().orEmpty()

    private fun loadDefault(): String? =
        runCatching {
            json.readText(FILE)?.let { text ->
                val o = JSONObject(text)
                if (o.isNull("defaultModelId")) null else o.optString("defaultModelId").ifEmpty { null }
            }
        }.getOrNull()

    companion object {
        const val FILE = "models.json"
    }
}
