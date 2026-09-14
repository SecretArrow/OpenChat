package com.openchat.android.ai

import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Automatic model-list discovery for any OpenAI-compatible / Anthropic
 * provider: GET `{baseUrl}{modelsPath}` with the right auth header, tolerant
 * response parsing, and a clean, key-scrubbed error surface.
 *
 * Supported response shapes (first match wins):
 *  1. OpenAI   `{"object":"list","data":[{"id":"gpt-4o"},…]}`
 *  2. Anthropic `{"data":[{"id":"claude-…","display_name":…},…]}`
 *  3. Generic  `{"models":[{"id"/"name"/"model":…},…]}`
 *  4. Bare array `["model-a","model-b"]` / `[{"id":…},…]`
 */
object ModelDiscovery {

    /** Discovery client — bounded call timeout so a hung endpoint can't stall the UI. */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }

    /**
     * Fetches the model ids for [provider]. [modelsPath] overrides the default
     * ("/v1/models" for ANTHROPIC, "/models" otherwise). [apiKeyHeader], when
     * given, forces a custom auth header (name to value) — used by presets
     * whose auth is `api_key` in a non-standard header (e.g. x-api-key on an
     * OpenAI-format endpoint). The key never appears in error messages.
     */
    suspend fun fetch(
        provider: Provider,
        apiKey: String?,
        modelsPath: String? = null,
        apiKeyHeader: Pair<String, String>? = null,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = provider.baseUrl.trim().trimEnd('/')
            require(base.isNotEmpty()) { "Base URL is empty — set the provider Base URL first" }
            val path = modelsPath?.takeIf { it.isNotBlank() }
                ?: if (provider.type == ProviderType.ANTHROPIC) "/v1/models" else "/models"
            val url = if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")

            val request = Http.newRequest(url)
                .header("Accept", "application/json")
                .apply {
                    val key = apiKey.orEmpty()
                    if (key.isNotBlank()) {
                        val forced = apiKeyHeader?.takeIf { !it.first.isBlank() }
                        when {
                            forced != null -> header(forced.first, forced.second)
                            provider.type == ProviderType.ANTHROPIC -> {
                                header("x-api-key", key)
                                header("anthropic-version", "2023-06-01")
                            }
                            else -> header("Authorization", "Bearer $key")
                        }
                    }
                }
                .apply { provider.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val peek = body.take(300)
                    throw IllegalStateException(
                        "HTTP ${response.code} from ${redactHost(url)}" +
                            if (peek.isBlank()) "" else " — ${Redact.scrub(peek, apiKey)}"
                    )
                }
                parseModelsResponse(body).ifEmpty {
                    throw IllegalStateException(
                        "No models found in response from ${redactHost(url)} (unsupported format?)"
                    )
                }
            }
        }.recoverCatching { t ->
            if (t is CancellationException) throw t
            if (t is IllegalArgumentException || t is IllegalStateException) throw t
            val info = Errors.network(t, "Model discovery")
            throw IllegalStateException(Redact.scrub(info.detail, apiKey))
        }
    }

    /** Tolerant model-id extraction — never throws, dedupes, sorts naturally. */
    fun parseModelsResponse(body: String): List<String> {
        val ids = LinkedHashSet<String>()
        runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                collectArray(JSONArray(trimmed), ids)
            } else {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("data")?.let { collectArray(it, ids) }
                if (ids.isEmpty()) obj.optJSONArray("models")?.let { collectArray(it, ids) }
                if (ids.isEmpty()) {
                    // Single-object model descriptor: {"id": "..."} / {"model": "..."}
                    listOf("id", "model", "name").firstNotNullOfOrNull { k ->
                        obj.optString(k).ifBlank { null }
                    }?.let { ids.add(it) }
                }
            }
        }
        return ids.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun collectArray(arr: JSONArray, out: LinkedHashSet<String>) {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> out.add(v)
                is JSONObject -> listOf("id", "model", "name", "modelId").firstNotNullOfOrNull { k ->
                    v.optString(k).ifBlank { null }
                }?.let { out.add(it) }
            }
        }
    }

    private fun redactHost(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}
