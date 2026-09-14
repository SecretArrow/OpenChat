package com.openchat.android.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bundled provider presets (provider-presets.schema.json v2).
 *
 * The catalog ships as `assets/provider_presets.json` and is parsed tolerantly:
 * a malformed entry is skipped, never fatal — a broken preset file must not
 * break provider management. Raw API keys are never part of a preset; users
 * type their own key on the edit screen (stored in SecretStore as usual).
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val protocols: List<String>,
    val defaultProtocol: String,
    val connections: Map<String, PresetConnection>,
    val discoveryEnabled: Boolean = true,
) {
    /** Concrete endpoint/auth bundle for one protocol ("openai" | "anthropic" | "auto"). */
    data class PresetConnection(
        val baseUrl: String,
        val authType: String,          // "api_key" | "bearer"
        val authHeader: String,        // "x-api-key" | "Authorization"
        val chatPath: String? = null,
        val responsesPath: String? = null,
        val modelsPath: String? = null,
        val customBaseUrl: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("baseUrl", baseUrl)
            .put("auth", JSONObject().put("type", authType).put("header", authHeader))
            .put("endpoints", JSONObject()
                .put("chat", chatPath ?: JSONObject.NULL)
                .put("responses", responsesPath ?: JSONObject.NULL)
                .put("models", modelsPath ?: JSONObject.NULL))
            .put("customBaseUrl", customBaseUrl)

        companion object {
            fun fromJson(o: JSONObject): PresetConnection {
                val auth = o.optJSONObject("auth") ?: JSONObject()
                val ep = o.optJSONObject("endpoints") ?: JSONObject()
                return PresetConnection(
                    baseUrl = o.optString("baseUrl"),
                    authType = auth.optString("type", "bearer").ifBlank { "bearer" },
                    authHeader = auth.optString("header", "Authorization").ifBlank { "Authorization" },
                    chatPath = ep.optString("chat").ifBlank { null },
                    responsesPath = ep.optString("responses").ifBlank { null },
                    modelsPath = ep.optString("models").ifBlank { null },
                    customBaseUrl = o.optBoolean("customBaseUrl", false),
                )
            }
        }
    }

    fun connection(protocol: String?): PresetConnection? =
        connections[resolveProtocolKey(protocol)]

    fun isMultiProtocol(): Boolean =
        connections.keys.filter { it != "auto" }.distinct().size > 1

    companion object {
        fun parse(json: String): List<ProviderPreset> = runCatching {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("providers") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())

        fun fromJson(o: JSONObject): ProviderPreset {
            val protocols = o.optJSONArray("protocols")?.let { a ->
                (0 until a.length()).mapNotNull { k -> a.optString(k).ifBlank { null } }
            } ?: emptyList()
            val conns = o.optJSONObject("connections") ?: JSONObject()
            val connMap = buildMap {
                conns.keys().forEach { k ->
                    runCatching {
                        put(k.lowercase(), PresetConnection.fromJson(conns.getJSONObject(k)))
                    }
                }
            }
            return ProviderPreset(
                id = o.optString("id").ifBlank { o.optString("name").lowercase().replace(' ', '-') },
                name = o.optString("name").ifBlank { "Provider" },
                protocols = protocols.map { it.lowercase() },
                defaultProtocol = o.optString("defaultProtocol", "auto").lowercase(),
                connections = connMap,
                discoveryEnabled = o.optJSONObject("discovery")?.optBoolean("enabled", true) ?: true,
            )
        }

        /** Asset file bundled with the APK. */
        const val ASSET = "provider_presets.json"

        /** Loads the bundled catalog; an unreadable/corrupt asset yields an empty list. */
        fun loadFromAssets(context: android.content.Context): List<ProviderPreset> =
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                parse(text)
            }.getOrDefault(emptyList())
    }
}

/** Pure mapping helpers preset → runtime [Provider] fields. JVM-testable. */
object PresetMapper {

    /** Picks the concrete protocol to use ("auto" resolved to the best concrete one). */
    fun resolveProtocol(preset: ProviderPreset, preferred: String?): String {
        val p = preferred?.lowercase()?.takeIf { it.isNotBlank() }
        if (p != null && preset.connections.containsKey(p)) return p
        val def = preset.defaultProtocol
        if (def != "auto" && preset.connections.containsKey(def)) return def
        // "auto" (or unknown default): prefer openai-compatible, then anthropic, then whatever exists.
        return when {
            preset.connections.containsKey("openai") -> "openai"
            preset.connections.containsKey("anthropic") -> "anthropic"
            else -> preset.connections.keys.firstOrNull() ?: "openai"
        }
    }

    /**
     * Chat transport for a preset/protocol pair:
     * anthropic wire format → [ProviderType.ANTHROPIC]; OpenRouter's openai
     * connection maps to [ProviderType.OPENROUTER] (correct defaults + headers);
     * everything else is a generic OpenAI-compatible endpoint.
     */
    fun providerType(preset: ProviderPreset, protocol: String?): ProviderType {
        val p = resolveProtocol(preset, protocol)
        return when {
            p == "anthropic" -> ProviderType.ANTHROPIC
            preset.id == "openrouter" && p == "openai" -> ProviderType.OPENROUTER
            else -> ProviderType.CUSTOM_OPENAI
        }
    }

    /** Model-discovery path (e.g. "/models"); anthropic wire uses "/v1/models". */
    fun modelsPath(preset: ProviderPreset, protocol: String?): String {
        val p = resolveProtocol(preset, protocol)
        val conn = preset.connection(p)
        return conn?.modelsPath?.takeIf { it.isNotBlank() }
            ?: if (p == "anthropic") "/v1/models" else "/models"
    }

    fun chatPath(preset: ProviderPreset, protocol: String?): String? =
        preset.connection(protocol)?.chatPath

    /** True when the key must go in a custom header (e.g. x-api-key) instead of Bearer. */
    fun usesApiKeyHeader(preset: ProviderPreset, protocol: String?): Boolean =
        preset.connection(protocol)?.authType == "api_key"

    fun apiKeyHeaderName(preset: ProviderPreset, protocol: String?): String =
        preset.connection(protocol)?.authHeader?.takeIf { it.isNotBlank() } ?: "x-api-key"

    /** Builds the pre-filled (key-less) [Provider] for a preset. */
    fun toProvider(preset: ProviderPreset, protocol: String?): Provider {
        val p = resolveProtocol(preset, protocol)
        val conn = preset.connection(p)
        return Provider(
            id = "",
            name = preset.name,
            type = providerType(preset, p),
            baseUrl = conn?.baseUrl.orEmpty(),
            headers = emptyMap(),
            enabled = true,
        )
    }
}
