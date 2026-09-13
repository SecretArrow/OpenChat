package com.openchat.android.ai.opencode

import com.openchat.android.ai.providers.ChatClients
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType

/**
 * Builds the OFFICIAL `opencode.json` configuration (schema
 * https://opencode.ai/config.json) that is written into the Ubuntu rootfs at
 * `/root/.config/opencode/opencode.json` (spec §29).
 *
 * Implemented with pure string building (no org.json) so it is unit-testable on
 * the plain JVM; JSON string escaping is handled by [jsonEscape].
 *
 * npm mapping (matches OpenCode's AI SDK providers):
 * - OPENAI / OPENROUTER / CUSTOM_OPENAI / OLLAMA → `@ai-sdk/openai-compatible`
 *   (for OLLAMA the baseURL gets Ollama's OpenAI-compatible `/v1` suffix)
 * - ANTHROPIC → `@ai-sdk/anthropic`
 * - GEMINI → `@ai-sdk/google`
 */
object OpenCodeConfigSync {

    /** AI SDK npm package for a provider type (public — used by tests and the controller). */
    fun npmFor(type: ProviderType): String = when (type) {
        ProviderType.ANTHROPIC -> "@ai-sdk/anthropic"
        ProviderType.GEMINI -> "@ai-sdk/google"
        ProviderType.OPENAI,
        ProviderType.OPENROUTER,
        ProviderType.CUSTOM_OPENAI,
        ProviderType.OLLAMA -> "@ai-sdk/openai-compatible"
    }

    /**
     * Builds the pretty-printed opencode.json content:
     *
     * ```
     * {
     *   "$schema": "https://opencode.ai/config.json",
     *   "model": "<providerId>/<modelName>",
     *   "provider": {
     *     "<providerId>": {
     *       "npm": "<npm package>",
     *       "name": "<providerName>",
     *       "options": { "baseURL": "<base>", "apiKey": "<key>" },
     *       "models": { "<modelName>": { "name": "<displayName>" } }
     *     }
     *   }
     * }
     * ```
     */
    fun build(provider: Provider, model: AIModel, apiKey: String): String {
        val base = when (provider.type) {
            ProviderType.OLLAMA ->
                ChatClients.openAiCompatBase(
                    provider.baseUrl.trim().trimEnd('/')
                        .ifEmpty { ChatClients.defaultBaseUrl(ProviderType.OLLAMA) }
                )
            else ->
                provider.baseUrl.trim().trimEnd('/')
                    .ifEmpty { ChatClients.defaultBaseUrl(provider.type) }
        }
        val npm = npmFor(provider.type)
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"\$schema\": \"").append(jsonEscape(SCHEMA)).append("\",\n")
        sb.append("  \"model\": \"")
            .append(jsonEscape(provider.id))
            .append("/")
            .append(jsonEscape(model.modelName))
            .append("\",\n")
        sb.append("  \"provider\": {\n")
        sb.append("    \"").append(jsonEscape(provider.id)).append("\": {\n")
        sb.append("      \"npm\": \"").append(jsonEscape(npm)).append("\",\n")
        sb.append("      \"name\": \"").append(jsonEscape(provider.name)).append("\",\n")
        sb.append("      \"options\": {\n")
        sb.append("        \"baseURL\": \"").append(jsonEscape(base)).append("\",\n")
        sb.append("        \"apiKey\": \"").append(jsonEscape(apiKey)).append("\"\n")
        sb.append("      },\n")
        sb.append("      \"models\": {\n")
        sb.append("        \"").append(jsonEscape(model.modelName)).append("\": {\n")
        sb.append("          \"name\": \"").append(jsonEscape(model.displayName)).append("\"\n")
        sb.append("        }\n")
        sb.append("      }\n")
        sb.append("    }\n")
        sb.append("  }\n")
        sb.append("}")
        return sb.toString()
    }

    /** Config schema URL written into the `$schema` field. */
    const val SCHEMA: String = "https://opencode.ai/config.json"

    /** True when [line] could collide with the heredoc delimiter used by the controller. */
    fun containsLine(text: String, line: String): Boolean =
        text.lineSequence().any { it == line }

    /** JSON string escaping: quotes, backslashes, control characters. */
    fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
