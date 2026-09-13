package com.openchat.android.ai

import com.openchat.android.core.model.Conversation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat backup format (spec §25/§26 spirit): one JSON document holding every
 * conversation exactly as persisted (loss-free round trip through the same
 * Conversation.toJson/fromJson the storage layer uses).
 *
 * Pure JVM object → unit-testable without Android (org.json real artifact).
 */
object ChatBackup {

    const val FORMAT = "openchat.chats"
    const val VERSION = 1

    fun toJson(conversations: List<Conversation>): JSONObject {
        val arr = JSONArray()
        conversations.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("count", conversations.size)
            .put("conversations", arr)
    }

    /**
     * Parses an export document. Skips conversations that fail to decode
     * (forward-compatible with newer fields); fails only when the envelope
     * itself is not a valid OpenChat chat backup.
     */
    fun parse(text: String): Result<List<Conversation>> = runCatching {
        val root = JSONObject(text)
        check(root.optString("format") == FORMAT) { "Not an OpenChat chat backup" }
        val arr = root.optJSONArray("conversations")
            ?: error("Backup contains no conversations array")
        val out = ArrayList<Conversation>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { Conversation.fromJson(arr.getJSONObject(i)) }
                .getOrNull()
                ?.takeIf { it.id.isNotBlank() }
                ?.let { out.add(it) }
        }
        out
    }
}
