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
 * Transport for [com.openchat.android.core.model.ProviderType.GEMINI]
 * (Google AI Studio / Generative Language API).
 *
 * Wire format: POST
 * `{base}/v1beta/models/{modelName}:streamGenerateContent?alt=sse&key=<key>` with
 * body `{contents:[{role:"user"|"model",parts:[{text}]}], systemInstruction?,
 * generationConfig:{maxOutputTokens, temperature}}`. SSE `data:` JSON carries the
 * text in `candidates[0].content.parts[*].text`. The API key travels only in the
 * URL (per Google's spec) and is never logged.
 */
object GeminiClient : ChatClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    override suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): Result<String> {
        val collected = StringBuilder()
        return try {
            val body = buildBody(req)
            val url = buildStreamUrl(req)
            val request = Http.newRequest(url)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { req.provider.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            SseHttp.withResponse(request) { response ->
                SseHttp.checkSuccessful(response, req.provider.name, req.apiKey)
                SseHttp.readLines(response) { raw ->
                    var keepGoing = true
                    for (payload in Sse.parseSseDataLines(raw)) {
                        Sse.parseGeminiError(payload)?.let { msg ->
                            throw ProviderException(
                                ErrorInfo(
                                    title = "Gemini stream error",
                                    detail = Redact.scrub(msg, req.apiKey),
                                    causes = listOf("The provider reported an error mid-stream"),
                                    suggestions = listOf("Retry", "Check model name and API key"),
                                    retryable = true,
                                )
                            )
                        }
                        val delta = Sse.parseGeminiDelta(payload)
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
            val base = p.baseUrl.trim().trimEnd('/').ifEmpty { ChatClients.defaultBaseUrl(p.type) }
            val url = "$base/v1beta/models" + if (apiKey.isNullOrBlank()) "" else "?key=$apiKey"
            val request = Http.newRequest(url)
                .header("Accept", "application/json")
                .apply { p.headers.forEach { (k, v) -> if (k.isNotBlank()) header(k, v) } }
                .build()
            SseHttp.withResponse(request) { response ->
                SseHttp.checkSuccessful(response, p.name, apiKey)
                val text = response.body?.string().orEmpty()
                val models = runCatching { JSONObject(text).optJSONArray("models") }.getOrNull()
                if (models != null) "OK — ${models.length()} models" else "OK"
            }.let { Result.success(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(toProviderError(t, "Connection test", apiKey))
        }
    }

    // ------------------------------------------------------------------ impl

    /**
     * Builds the GenerateContent request body. SYSTEM messages become
     * `systemInstruction`; USER → role "user", ASSISTANT/TOOL → role "model".
     * Consecutive same-role messages are merged into one `contents` entry because
     * the API requires alternating roles.
     */
    private fun buildBody(req: ChatRequest): JSONObject {
        val systemText = req.messages
            .filter { it.role == Role.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n") { it.content }

        val contents = JSONArray()
        fun appendTurn(role: String, text: String) {
            val last = if (contents.length() > 0) contents.optJSONObject(contents.length() - 1) else null
            if (last != null && last.optString("role") == role) {
                val parts = last.optJSONArray("parts") ?: JSONArray().also { last.put("parts", it) }
                val first = parts.optJSONObject(0)
                if (first != null) first.put("text", first.optString("text") + text)
                else parts.put(JSONObject().put("text", text))
            } else {
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
        }

        req.messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> Unit // handled via systemInstruction
                Role.USER -> if (m.content.isNotBlank()) appendTurn("user", m.content)
                Role.ASSISTANT, Role.TOOL -> if (m.content.isNotBlank()) appendTurn("model", m.content)
            }
        }

        return JSONObject().apply {
            put("contents", contents)
            if (systemText.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                )
            }
            put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", req.maxTokens)
                    .put("temperature", req.temperature)
            )
        }
    }

    private fun buildStreamUrl(req: ChatRequest): String {
        val base = req.provider.baseUrl.trim().trimEnd('/')
            .ifEmpty { ChatClients.defaultBaseUrl(req.provider.type) }
        val key = req.apiKey.trim()
        val suffix = ":streamGenerateContent?alt=sse" + if (key.isEmpty()) "" else "&key=$key"
        return "$base/v1beta/models/${req.model.modelName}$suffix"
    }
}
