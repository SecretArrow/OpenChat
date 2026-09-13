package com.openchat.android.ai.providers

import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.model.Role
import com.openchat.android.core.net.Http
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport for every OpenAI-compatible provider:
 * [ProviderType.OPENAI], [ProviderType.OPENROUTER], [ProviderType.CUSTOM_OPENAI]
 * and [ProviderType.OLLAMA] (via Ollama's built-in `/v1` OpenAI-compatible API).
 *
 * Wire format: POST `{base}/chat/completions` with
 * `Authorization: Bearer <key>` plus the provider's extra headers, body
 * `{model, messages[], max_tokens, temperature, stream:true}` and SSE deltas from
 * `choices[0].delta.content` until the `data: [DONE]` sentinel.
 */
object OpenAICompatClient : ChatClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val DONE = "[DONE]"

    override suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): Result<String> {
        val collected = StringBuilder()
        return try {
            val body = JSONObject().apply {
                put("model", req.model.modelName)
                put("messages", JSONArray().apply {
                    req.messages.forEach { m ->
                        put(
                            JSONObject()
                                .put("role", wireRole(m.role))
                                .put("content", m.content)
                        )
                    }
                })
                put("max_tokens", req.maxTokens)
                put("temperature", req.temperature)
                put("stream", true)
            }
            val request = Http.newRequest(resolveBase(req.model.baseUrl, req.provider) + "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { if (req.apiKey.isNotBlank()) header("Authorization", "Bearer " + req.apiKey) }
                .apply { req.provider.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            SseHttp.withResponse(request) { response ->
                SseHttp.checkSuccessful(response, req.provider.name, req.apiKey)
                SseHttp.readLines(response) { raw ->
                    var keepGoing = true
                    for (payload in Sse.parseSseDataLines(raw)) {
                        if (payload.trim() == DONE) {
                            keepGoing = false
                            break
                        }
                        val delta = Sse.parseOpenAIDelta(payload)
                        if (!delta.isNullOrEmpty()) {
                            collected.append(delta)
                            onDelta(delta)
                        }
                    }
                    keepGoing
                }
            }
            Result.success(collected.toString())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(toProviderError(t, "Chat", req.apiKey))
        }
    }

    override suspend fun testConnection(p: Provider, apiKey: String?): Result<String> {
        return try {
            val request = Http.newRequest(resolveBase(p.baseUrl, p) + "/models")
                .header("Accept", "application/json")
                .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
                .apply { p.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .build()
            SseHttp.withResponse(request) { response ->
                SseHttp.checkSuccessful(response, p.name, apiKey)
                val text = response.body?.string().orEmpty()
                val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull()
                if (data != null) "OK — ${data.length()} models" else "OK"
            }.let { Result.success(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(toProviderError(t, "Connection test", apiKey))
        }
    }

    // ------------------------------------------------------------------ impl

    /**
     * Base URL resolution: model override → provider Base URL → type default.
     * OLLAMA always gets the `/v1` compatibility suffix appended.
     */
    private fun resolveBase(rawUrl: String?, p: Provider): String {
        val base = (rawUrl ?: p.baseUrl).trim().trimEnd('/')
        return when (p.type) {
            ProviderType.OLLAMA ->
                ChatClients.openAiCompatBase(base.ifEmpty { ChatClients.defaultBaseUrl(ProviderType.OLLAMA) })
            ProviderType.OPENAI ->
                base.ifEmpty { ChatClients.defaultBaseUrl(ProviderType.OPENAI) }
            ProviderType.OPENROUTER ->
                base.ifEmpty { ChatClients.defaultBaseUrl(ProviderType.OPENROUTER) }
            else -> {
                if (base.isEmpty()) {
                    throw ProviderException(
                        com.openchat.android.core.model.ErrorInfo(
                            title = "Base URL missing",
                            detail = "Provider '${p.name}' (${p.type}) requires a Base URL, e.g. http://your-server:8000/v1.",
                            causes = listOf("Custom OpenAI-compatible servers have no default address"),
                            suggestions = listOf("Edit the provider in Settings → Providers and set its Base URL"),
                            retryable = false,
                        )
                    )
                }
                base
            }
        }
    }

    /** Domain role → OpenAI wire role (TOOL messages are surfaced as user context). */
    private fun wireRole(role: Role): String = when (role) {
        Role.SYSTEM -> "system"
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "user"
    }
}
