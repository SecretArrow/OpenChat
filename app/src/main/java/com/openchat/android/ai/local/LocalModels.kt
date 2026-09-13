package com.openchat.android.ai.local

import com.openchat.android.core.model.AIModel
import org.json.JSONObject

/**
 * One on-device model (spec §10/§11 spirit, applied to local GGUF inference):
 * either a CATALOG entry pointing at a verified HuggingFace GGUF file, or an
 * IMPORTED model (user-provided .gguf via SAF).
 *
 * Every catalog URL below was verified live (HTTP 200 + byte size) before
 * shipping — no invented entries (spec §32). Sizes are the verified
 * content-length rounded to whole MB.
 */
data class LocalModelSpec(
    val id: String,            // stable id; GGUF file is stored as <id>.gguf
    val name: String,
    val repo: String?,         // HuggingFace repo ("owner/name"); null for imported
    val file: String?,         // GGUF file inside [repo]; null for imported
    val sizeBytes: Long,
    val params: String,        // human, e.g. "1.5B"
    val quant: String,         // e.g. "Q4_K_M"
    val notes: String,         // honest, plain-language description
    val source: String,        // SOURCE_CATALOG or SOURCE_IMPORTED
    val downloadedAt: Long? = null,
) {
    /** Direct download URL on HuggingFace (null for imported models). */
    val url: String?
        get() = repo?.let { r -> file?.let { f -> "https://huggingface.co/$r/resolve/main/$f" } }

    /** Honest RAM hint: weights + KV/activation overhead for a 4096 ctx. */
    val ramHintMb: Int
        get() = (sizeBytes / (1024L * 1024L)).toInt() + 350

    /** AIModel-compatible id used in conversations (never collides with cloud ids). */
    val aiModelId: String get() = aiId(id)

    /** Adapter so the chat layer can treat local models like any other model. */
    fun toAIModel(): AIModel = AIModel(
        id = aiModelId,
        displayName = name,
        providerId = "local",
        modelName = file ?: "$name.gguf",
        contextLength = 4096,
        maxTokens = 1024,
        temperature = 0.7,
        reasoning = id.startsWith("deepseek-r1"),
        enabled = true,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("repo", repo ?: JSONObject.NULL)
        .put("file", file ?: JSONObject.NULL)
        .put("sizeBytes", sizeBytes)
        .put("params", params)
        .put("quant", quant)
        .put("notes", notes)
        .put("source", source)
        .put("downloadedAt", downloadedAt ?: JSONObject.NULL)

    companion object {
        const val SOURCE_CATALOG = "CATALOG"
        const val SOURCE_IMPORTED = "IMPORTED"

        /** Prefix for conversation model ids that resolve to on-device models. */
        const val AI_PREFIX = "local:"

        fun aiId(id: String) = "$AI_PREFIX$id"

        fun fromJson(o: JSONObject): LocalModelSpec = LocalModelSpec(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            repo = if (o.isNull("repo")) null else o.optString("repo"),
            file = if (o.isNull("file")) null else o.optString("file"),
            sizeBytes = o.optLong("sizeBytes", 0L),
            params = o.optString("params", "?"),
            quant = o.optString("quant", "?"),
            notes = o.optString("notes", ""),
            source = if (o.optString("source") == SOURCE_IMPORTED) SOURCE_IMPORTED else SOURCE_CATALOG,
            downloadedAt = if (o.isNull("downloadedAt")) null else o.optLong("downloadedAt"),
        )
    }
}

/**
 * History trimming before a local completion: the engine uses a 4096-token
 * context; ~9000 characters is a safe prompt budget (≈2.2–3k tokens) that
 * always keeps the newest message (the user's request).
 */
object LocalHistory {
    const val CHAR_BUDGET = 9000

    /**
     * @param history ordered (role, content) pairs; role is "system"/"user"/"assistant".
     * @return trimmed list, original order preserved, never empty, newest entry kept.
     */
    fun trim(history: List<Pair<String, String>>): List<Pair<String, String>> {
        if (history.isEmpty()) return history
        if (history.sumOf { it.second.length } <= CHAR_BUDGET) return history

        val newest = history.last()
        val system = history.firstOrNull { it.first == "system" && it !== newest }
        val body = history.asReversed().filterNot { it === system || it === newest }

        val kept = ArrayList<Pair<String, String>>()
        var total = newest.second.length
        if (system != null) total += system.second.length
        for (m in body) {
            val l = m.second.length
            if (total + l > CHAR_BUDGET) break
            kept.add(m)
            total += l
        }
        val out = ArrayList<Pair<String, String>>()
        if (system != null) out.add(system)
        out.addAll(kept.asReversed())
        out.add(newest)
        return out
    }
}

/**
 * Curated catalog (≤6B params, GGUF, trending families on HuggingFace,
 * URLs + sizes verified live). Coding models come first — Open Chat is a
 * coding environment.
 */
object LocalCatalog {
    private fun mb(v: Int) = v.toLong() * 1024L * 1024L

    val MODELS: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "qwen2.5-coder-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 Coder 1.5B Instruct",
            repo = "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            file = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = mb(1065),
            params = "1.5B",
            quant = "Q4_K_M",
            notes = "Best coding model for this size — writes and explains code well. Great default for Open Chat.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "qwen2.5-coder-3b-instruct-q4_k_m",
            name = "Qwen2.5 Coder 3B Instruct",
            repo = "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
            file = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            sizeBytes = mb(2007),
            params = "3B",
            quant = "Q4_K_M",
            notes = "Stronger coding than the 1.5B. Needs ≈2.4 GB free RAM — for 6 GB+ devices.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "llama-3.2-1b-instruct-q4_k_m",
            name = "Llama 3.2 1B Instruct",
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            file = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = mb(770),
            params = "1B",
            quant = "Q4_K_M",
            notes = "Fastest general assistant; good choice for phones with little RAM.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "llama-3.2-3b-instruct-q4_k_m",
            name = "Llama 3.2 3B Instruct",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            file = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = mb(1925),
            params = "3B",
            quant = "Q4_K_M",
            notes = "Balanced general model. Needs ≈2.3 GB free RAM.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "gemma-2-2b-it-q4_k_m",
            name = "Gemma 2 2B IT",
            repo = "bartowski/gemma-2-2b-it-GGUF",
            file = "gemma-2-2b-it-Q4_K_M.gguf",
            sizeBytes = mb(1629),
            params = "2.6B",
            quant = "Q4_K_M",
            notes = "Google's small model with strong reasoning for its size.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 1.5B Instruct",
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            file = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = mb(1065),
            params = "1.5B",
            quant = "Q4_K_M",
            notes = "General chat assistant; multilingual.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "smollm2-1.7b-instruct-q4_k_m",
            name = "SmolLM2 1.7B Instruct",
            repo = "bartowski/SmolLM2-1.7B-Instruct-GGUF",
            file = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            sizeBytes = mb(1006),
            params = "1.7B",
            quant = "Q4_K_M",
            notes = "Compact assistant from HuggingFace; quick on mid-range phones.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b-q4_k_m",
            name = "DeepSeek R1 Distill Qwen 1.5B",
            repo = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            file = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeBytes = mb(1065),
            params = "1.5B",
            quant = "Q4_K_M",
            notes = "Reasoning model — thinks step by step before answering (slower, more verbose).",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
        LocalModelSpec(
            id = "phi-3.5-mini-instruct-q4_k_m",
            name = "Phi-3.5 Mini Instruct",
            repo = "bartowski/Phi-3.5-mini-instruct-GGUF",
            file = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sizeBytes = mb(2282),
            params = "3.8B",
            quant = "Q4_K_M",
            notes = "Microsoft's 3.8B model; strong quality for its size, needs ≈2.7 GB free RAM.",
            source = LocalModelSpec.SOURCE_CATALOG,
        ),
    )
}
