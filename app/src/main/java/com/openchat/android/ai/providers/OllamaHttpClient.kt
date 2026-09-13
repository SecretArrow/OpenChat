package com.openchat.android.ai.providers

import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.OllamaModel
import com.openchat.android.core.model.Role
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Low-level Ollama HTTP calls (spec §11 — local AND remote servers are first-class).
 *
 * Functions throw [ProviderException] on failure (managers wrap them into
 * [Result]). Base URLs are normalized (no trailing slash); API keys are used for
 * `Authorization: Bearer` when present and are NEVER logged or embedded in
 * error messages (scrubbed via [Redact.scrub]).
 */
object OllamaHttpClient {

    /** GET `{base}/api/tags` → the models installed on the server. */
    suspend fun listTags(base: String, apiKey: String? = null): List<OllamaModel> {
        val url = normalize(base) + "/api/tags"
        return SseHttp.withResponse(request(url, apiKey = apiKey)) { response ->
            SseHttp.checkSuccessful(response, "Ollama", apiKey)
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("models")
                ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OllamaModel(
                    name = o.optString("name"),
                    sizeBytes = o.optLong("size", 0L),
                    digest = o.optString("digest").ifEmpty { null },
                    modifiedAt = o.optString("modified_at").ifEmpty { null },
                )
            }
        }
    }

    /**
     * POST `{base}/api/pull` (streaming). Progress lines are translated to
     * "pulling <name>: 45%" (when total/completed are present) or the raw status
     * text (e.g. "pulling manifest", "success"). An `error` field fails the pull.
     */
    suspend fun pull(base: String, name: String, onProgress: (String) -> Unit, apiKey: String? = null) {
        val url = normalize(base) + "/api/pull"
        val body = JSONObject().put("name", name).put("model", name).put("stream", true)
        SseHttp.withResponse(
            request(url, method = "POST", bodyJson = body, apiKey = apiKey)
        ) { response ->
            SseHttp.checkSuccessful(response, "Ollama", apiKey)
            SseHttp.readLines(response) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@readLines true
                Sse.parseOllamaError(line)?.let { msg ->
                    throw ProviderException(errorInfo(Redact.scrub(msg, apiKey)))
                }
                val status = o.optString("status")
                val total = o.optLong("total", 0L)
                val completed = o.optLong("completed", 0L)
                val message = if (status.isNotEmpty() && total > 0 && completed > 0) {
                    "pulling $name: ${completed * 100 / total}%"
                } else {
                    status
                }
                if (message.isNotEmpty()) onProgress(message)
                true
            }
        }
    }

    /** DELETE `{base}/api/delete` with `{"name": ...}` — removes a model from the server. */
    suspend fun deleteModel(base: String, name: String, apiKey: String? = null) {
        val url = normalize(base) + "/api/delete"
        val body = JSONObject().put("name", name)
        SseHttp.withResponse(request(url, method = "DELETE", bodyJson = body, apiKey = apiKey)) { response ->
            SseHttp.checkSuccessful(response, "Ollama", apiKey)
            response.body?.string()
        }
    }

    /**
     * POST `{base}/api/chat` (streaming JSON lines). Deltas are appended from
     * `message.content` until a line with `done: true`. Returns the full reply.
     */
    suspend fun chat(
        base: String,
        messages: List<ChatMessage>,
        model: String,
        onDelta: (String) -> Unit,
        apiKey: String? = null,
    ): String {
        val url = normalize(base) + "/api/chat"
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    if (m.content.isBlank()) return@forEach
                    put(
                        JSONObject()
                            .put("role", wireRole(m.role))
                            .put("content", m.content)
                    )
                }
            })
        }
        val collected = StringBuilder()
        SseHttp.withResponse(
            request(url, method = "POST", bodyJson = body, apiKey = apiKey)
        ) { response ->
            SseHttp.checkSuccessful(response, "Ollama", apiKey)
            SseHttp.readLines(response) { line ->
                Sse.parseOllamaError(line)?.let { msg ->
                    throw ProviderException(errorInfo(Redact.scrub(msg, apiKey)))
                }
                if (Sse.parseOllamaDone(line)) return@readLines false
                val delta = Sse.parseOllamaChatDelta(line)
                if (!delta.isNullOrEmpty()) {
                    collected.append(delta)
                    onDelta(delta)
                }
                true
            }
        }
        return collected.toString()
    }

    /**
     * POST `{base}/api/generate` with an empty prompt and `keep_alive: "10m"` —
     * loads the model into memory so the next chat request answers instantly.
     */
    suspend fun runLoaded(base: String, name: String, apiKey: String? = null): String {
        val url = normalize(base) + "/api/generate"
        val body = JSONObject().put("model", name).put("keep_alive", "10m")
        return SseHttp.withResponse(
            request(url, method = "POST", bodyJson = body, apiKey = apiKey)
        ) { response ->
            SseHttp.checkSuccessful(response, "Ollama", apiKey)
            response.body?.string().orEmpty()
        }.let { "Model '$name' loaded (keep_alive 10m)" }
    }

    /** GET `{base}/api/tags` — connectivity probe. Returns a human message. */
    suspend fun testConnection(base: String, apiKey: String? = null): String {
        val models = listTags(base, apiKey)
        return "OK — ${models.size} models"
    }

    // ------------------------------------------------------------------ impl

    /** Normalizes the server base URL: trimmed, no trailing slash, non-empty. */
    fun normalize(raw: String): String {
        val b = raw.trim().trimEnd('/')
        if (b.isEmpty()) {
            throw ProviderException(
                ErrorInfo(
                    title = "Ollama URL missing",
                    detail = "The Ollama server has no Base URL configured.",
                    causes = listOf("Server entry created without an address"),
                    suggestions = listOf("Edit the server in Settings → Ollama and set e.g. http://192.168.1.10:11434"),
                    retryable = false,
                )
            )
        }
        return b
    }

    private fun request(
        url: String,
        method: String = "GET",
        bodyJson: JSONObject? = null,
        apiKey: String?,
    ): Request {
        val builder = Http.newRequest(url).header("Accept", "application/json")
        when (method) {
            "POST" -> builder.post(
                (bodyJson?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            "DELETE" -> builder.delete(
                (bodyJson?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }
        if (!apiKey.isNullOrBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    /** Maps transport failures to actionable ErrorInfo; connect failures hint at OLLAMA_HOST. */
    internal fun errorInfo(detail: String): ErrorInfo = ErrorInfo(
        title = "Ollama error",
        detail = detail,
        causes = listOf("The Ollama server rejected the request or reported an error"),
        suggestions = listOf("Check the model name (e.g. `llama3.1:8b`)", "Check disk space on the server", "Inspect the server logs"),
        retryable = true,
    )

    internal fun toOllamaError(t: Throwable, baseUrl: String, vararg secrets: String?): ProviderException =
        if (t is ProviderException) t
        else {
            val info = when (t) {
                is UnknownHostException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketTimeoutException -> Errors.ollamaConnection(baseUrl.trim().trimEnd('/'))
                is IOException -> Errors.network(t, "Ollama request")
                else -> Errors.network(t, "Ollama request")
            }
            ProviderException(info.copy(detail = Redact.scrub(info.detail, *secrets)))
        }

    private fun wireRole(role: Role): String = when (role) {
        Role.SYSTEM -> "system"
        Role.USER, Role.TOOL -> "user"
        Role.ASSISTANT -> "assistant"
    }
}
