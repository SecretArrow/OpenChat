package com.openchat.android.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp factory. One client, connection pool reused across providers
 * (spec §26 — avoid duplicated resources).
 */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Client tuned for long SSE streams. */
    val streaming: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    fun newRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", "OpenChat/0.1 (Android)")

    /** Okio buffered source of a response body (null when absent). */
    fun source(response: Response): okio.BufferedSource? =
        response.body?.source()

    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')
}
