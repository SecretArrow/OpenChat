package com.openchat.android.ai.providers

import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.Role
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Redact
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport for [com.openchat.android.core.model.ProviderType.ANTHROPIC].
 *
 * Wire format: POST `{base}/v1/messages` with headers `x-api-key` and
 * `anthropic-version: 2023-06-01`. SYSTEM messages are joined into the top-level
 * `system` field; the rest go to `messages[]` (`user`/`assistant`). Streaming uses
 * SSE where `content_block_delta` events carry `delta.text`; `message_stop` ends
 * the stream and `error` events fail the request.
 */
object AnthropicClient : ChatClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val ANTHROPIC_VERSION = "2023-06-01"

    override suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): Result<String> {
        val collected = StringBuilder()
        return try {
            val system = req.messages
                .filter { it.role == Role.SYSTEM && it.content.isNotBlank() }
                .joinToString("\n") { it.content }
            val messages = JSONArray().apply {
                req.messages.forEach { m ->
                    when (m.role) {
                        Role.SYSTEM -> Unit // moved to the "system" field above
                        else -> if (m.content.isNotBlank() || m.attachments.isNotEmpty()) {
                            val images = com.openchat.android.ai.AttachmentFiles.imagesOf(m.attachments)
                            val content: Any = if (images.isEmpty()) {
                                m.content
                            } else {
                                // Anthropic vision shape: content blocks with
                                // base64 image sources.
                                JSONArray().apply {
                                    if (m.content.isNotBlank()) {
                                        put(JSONObject().put("type", "text").put("text", m.content))
                                    }
                                    images.forEach { img ->
                                        val b64 = com.openchat.android.ai.AttachmentFiles.imageBase64(img)
                                        if (b64 != null) {
                                            put(
                                                JSONObject()
                                                    .put("type", "image")
                                                    .put(
                                                        "source",
                                                        JSONObject()
                                                            .put("type", "base64")
                                                            .put("media_type", img.mime)
                                                            .put("data", b64),
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                            put(
                                JSONObject()
                                    .put("role", wireRole(m.role))
                                    .put("content", content)
                            )
                        }
                    }
                }
            }
            val body = JSONObject().apply {
                put("model", req.model.modelName)
                put("max_tokens", req.maxTokens)
                put("temperature", req.temperature)
                put("stream", true)
                if (system.isNotBlank()) put("system", system)
                put("messages", messages)
            }
            val request = Http.newRequest(resolveBase(req.provider) + "/v1/messages")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-api-key", req.apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .apply { req.provider.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            SseHttp.withResponse(request) { response ->
                SseHttp.checkSuccessful(response, req.provider.name, req.apiKey)
                SseHttp.readLines(response) { raw ->
                    var keepGoing = true
                    for (payload in Sse.parseSseDataLines(raw)) {
                        Sse.parseAnthropicError(payload)?.let { msg ->
                            throw ProviderException(
                                ErrorInfo(
                                    title = "Anthropic stream error",
                                    detail = Redact.scrub(msg, req.apiKey),
                                    causes = listOf("The provider reported an error mid-stream"),
                                    suggestions = listOf("Retry", "Check model name and quota"),
                                    retryable = true,
                                )
                            )
                        }
                        if (Sse.parseAnthropicStop(payload)) {
                            keepGoing = false
                            break
                        }
                        val delta = Sse.parseAnthropicDelta(payload)
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
            val request = Http.newRequest(resolveBase(p) + "/v1/models")
                .header("Accept", "application/json")
                .header("x-api-key", apiKey ?: "")
                .header("anthropic-version", ANTHROPIC_VERSION)
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

    /** Base URL: provider value, or https://api.anthropic.com (call sites append /v1/...). */
    private fun resolveBase(p: Provider): String =
        p.baseUrl.trim().trimEnd('/').ifEmpty { ChatClients.defaultBaseUrl(p.type) }

    private fun wireRole(role: Role): String = when (role) {
        Role.SYSTEM -> "user"   // never reached (SYSTEM handled separately); defensive
        Role.USER, Role.TOOL -> "user"
        Role.ASSISTANT -> "assistant"
    }
}
