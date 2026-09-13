package com.openchat.android.core.util

import android.util.Log

/**
 * Log/secret redaction helpers (spec §12, §23).
 * Raw secret values must never reach logs or the UI.
 */
object Redact {

    const val MASK = "••••••••••••"

    /** Full masking for display of stored keys. */
    fun secret(value: String?): String =
        if (value.isNullOrBlank()) "(not set)" else MASK

    /** Partial id for logs: show first 3 chars only. */
    fun idOf(id: String): String =
        if (id.length <= 3) "***" else id.take(3) + "***"

    /** Replace every occurrence of the given secret values inside [text]. */
    fun scrub(text: String, vararg secrets: String?): String {
        var out = text
        for (s in secrets) {
            if (!s.isNullOrBlank() && s.length >= 4) out = out.replace(s, MASK)
        }
        return out
    }

    fun d(tag: String, msg: String) = Log.d(tag, msg)
    fun i(tag: String, msg: String) = Log.i(tag, msg)
    fun w(tag: String, msg: String) = Log.w(tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(tag, msg, t)
}
