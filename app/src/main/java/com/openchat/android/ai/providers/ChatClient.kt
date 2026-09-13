package com.openchat.android.ai.providers

import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Failure carrying a structured, actionable [ErrorInfo] (spec §24).
 * Managers surface `info` directly to the UI; the raw key never appears in `info`
 * (callers scrub with [Redact.scrub]).
 */
class ProviderException(val info: ErrorInfo) : Exception(info.detail)

/** Maps any throwable into a [ProviderException], keeping structured errors as-is. */
internal fun toProviderError(t: Throwable, context: String, vararg secrets: String?): ProviderException =
    if (t is ProviderException) t
    else {
        val info = Errors.network(t, context)
        ProviderException(info.copy(detail = Redact.scrub(info.detail, *secrets)))
    }

/** Request for a streaming chat completion (contract: ai/providers/ChatClient.kt). */
data class ChatRequest(
    val model: AIModel,
    val provider: Provider,
    val apiKey: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
)

/**
 * Provider-specific chat transport (contract: ai/providers/ChatClient.kt).
 *
 * Implementations must never block the caller thread (all work on Dispatchers.IO),
 * must honour coroutine cancellation by cancelling the underlying OkHttp call, and
 * must map every failure to a [ProviderException] carrying an actionable
 * [ErrorInfo] — with any API key scrubbed out of the message.
 */
interface ChatClient {
    /**
     * Streams a completion, invoking [onDelta] on the calling dispatcher for every
     * text delta. Returns the full accumulated text; failures are returned as
     * [Result.failure] with a [ProviderException] (except cancellation, which is
     * rethrown as [CancellationException]).
     */
    suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): Result<String>

    /** Connectivity/auth probe; success message like "OK — 12 models". */
    suspend fun testConnection(p: Provider, apiKey: String?): Result<String>
}

/**
 * Factory for provider transports (contract: ai/providers/ChatClient.kt).
 *
 * OLLAMA is served by [OpenAICompatClient] through Ollama's built-in
 * OpenAI-compatible endpoint (`{base}/v1`) — the same mapping OpenCode uses
 * (`@ai-sdk/openai-compatible`).
 */
object ChatClients {

    /** Returns the transport for a provider type. */
    fun forProvider(type: ProviderType): ChatClient = when (type) {
        ProviderType.OPENAI,
        ProviderType.OPENROUTER,
        ProviderType.CUSTOM_OPENAI,
        ProviderType.OLLAMA -> OpenAICompatClient
        ProviderType.ANTHROPIC -> AnthropicClient
        ProviderType.GEMINI -> GeminiClient
    }

    /** Default Base URL per provider type when none is configured (CUSTOM_OPENAI requires one). */
    fun defaultBaseUrl(type: ProviderType): String = when (type) {
        ProviderType.OPENAI -> "https://api.openai.com/v1"
        ProviderType.ANTHROPIC -> "https://api.anthropic.com"
        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
        ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
        ProviderType.OLLAMA -> "http://127.0.0.1:11434"
        ProviderType.CUSTOM_OPENAI -> ""
    }

    /**
     * Appends Ollama's OpenAI-compatible `/v1` suffix, collapsing a `/v1` that
     * the user already provided (so `http://host:11434/v1` never becomes
     * `http://host:11434/v1/v1`).
     */
    fun openAiCompatBase(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        val stripped = if (b.length > 3 && b.substring(b.length - 3).equals("/v1", ignoreCase = true)) {
            b.substring(0, b.length - 3)
        } else {
            b
        }
        return "$stripped/v1"
    }
}

/**
 * Internal OkHttp plumbing shared by all AI transports.
 *
 * - Every call runs on [Dispatchers.IO]; the caller thread is never blocked.
 * - Coroutine cancellation aborts the in-flight OkHttp call (`call.cancel()`),
 *   so SSE streams stop immediately and no goroutine/thread leaks.
 * - Non-2xx responses throw [ProviderException] with a mapped [ErrorInfo]
 *   (401/403 → Errors.providerAuth) and the API key scrubbed from any body text.
 */
internal object SseHttp {

    /**
     * Executes [request] and hands the [Response] to [block] on an IO thread.
     * [block] must be non-suspending; it may block on the body stream — a
     * coroutine cancellation aborts that blocking read via `call.cancel()`.
     */
    suspend fun <T> withResponse(
        request: Request,
        client: OkHttpClient = Http.streaming,
        block: (Response) -> T,
    ): T {
        val call = client.newCall(request)
        return try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    try {
                        val response = call.execute()
                        val result = try {
                            block(response)
                        } finally {
                            runCatching { response.closeQuietly() }
                        }
                        if (cont.isActive) cont.resume(result)
                    } catch (t: Throwable) {
                        // If the coroutine was cancelled the continuation is already
                        // completed — resuming it is a no-op, so guard explicitly.
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        }
    }

    /**
     * Reads the response body line-by-line (okio). Each raw line goes to [onLine];
     * return `false` from [onLine] to stop reading early (e.g. on a `[DONE]`
     * sentinel or a terminal event). Blocks the IO thread — cancelled together
     * with the coroutine through [withResponse]'s cancellation hook.
     */
    fun readLines(response: Response, onLine: (String) -> Boolean) {
        val source = Http.source(response)
            ?: throw ProviderException(
                ErrorInfo(
                    title = "Empty response body",
                    detail = "The provider returned a response without a readable body.",
                    causes = listOf("Server closed the connection early", "Proxy stripped the body"),
                    suggestions = listOf("Retry", "Check the Base URL points at a streaming-capable endpoint"),
                    retryable = true,
                )
            )
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!onLine(line)) break
        }
    }

    /**
     * Ensures the response is HTTP 2xx; otherwise throws [ProviderException] with
     * a specific [ErrorInfo]. [secrets] are scrubbed from the error body snippet.
     */
    fun checkSuccessful(response: Response, providerName: String, vararg secrets: String?): Response {
        if (response.isSuccessful) return response
        val rawBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val snippet = Redact.scrub(rawBody.take(500), *secrets)
        val code = response.code
        val info: ErrorInfo = when (code) {
            401, 403 -> Errors.providerAuth(providerName).copy(
                detail = "The provider rejected the credentials (HTTP $code). ${snippet.take(200)}".trim()
            )
            429 -> ErrorInfo(
                title = "Rate limited (HTTP 429)",
                detail = snippet.ifBlank { "The provider is rate-limiting this account." },
                causes = listOf("Too many requests in a short time", "Monthly quota exceeded"),
                suggestions = listOf("Wait a moment and retry", "Check your provider plan/billing page"),
                retryable = true,
            )
            404 -> ErrorInfo(
                title = "Not found (HTTP 404)",
                detail = snippet.ifBlank { "The endpoint or model does not exist." },
                causes = listOf("Wrong Base URL (e.g. missing or extra /v1)", "Model name not available on this provider"),
                suggestions = listOf(
                    "Verify the Base URL, e.g. https://api.openai.com/v1",
                    "Check the exact model name in the provider console"
                ),
                retryable = false,
            )
            in 500..599 -> ErrorInfo(
                title = "Provider server error (HTTP $code)",
                detail = snippet.ifBlank { "The provider had an internal error." },
                causes = listOf("Temporary provider outage", "Model overloaded"),
                suggestions = listOf("Retry in a few seconds", "Try a different model"),
                retryable = true,
            )
            else -> ErrorInfo(
                title = "Request failed (HTTP $code)",
                detail = snippet.ifBlank { "The provider returned an unexpected status." },
                causes = listOf("See provider response body"),
                suggestions = listOf("Check Base URL, API key and model name", "Open logs for the full response"),
                retryable = true,
            )
        }
        throw ProviderException(info)
    }
}

private fun Response.closeQuietly() {
    runCatching { close() }
}
