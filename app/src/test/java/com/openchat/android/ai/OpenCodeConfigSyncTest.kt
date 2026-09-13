package com.openchat.android.ai

import com.openchat.android.ai.opencode.OpenCodeConfigSync
import com.openchat.android.ai.providers.MiniJson
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-JVM tests for [OpenCodeConfigSync] (spec §29, INTEGRATION.md test contract).
 *
 * The produced opencode.json is parsed with the bundled [MiniJson] instead of
 * org.json because the Android unit-test classpath only ships stubbed org.json
 * classes — MiniJson is the same real parser the SSE clients use.
 */
class OpenCodeConfigSyncTest {

    // ------------------------------------------------------------------ helpers

    private fun provider(type: ProviderType, baseUrl: String): Provider = Provider(
        id = "prov-" + type.name.lowercase(),
        name = "Prov " + type.name,
        type = type,
        baseUrl = baseUrl,
    )

    private fun model(providerId: String, modelName: String): AIModel = AIModel(
        id = "model-1",
        displayName = "My Model",
        providerId = providerId,
        modelName = modelName,
    )

    private fun parse(json: String): Map<*, *> {
        val value = MiniJson.parse(json)
        return value as? Map<*, *>
            ?: throw AssertionError("opencode.json is not a JSON object: $json")
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

    private fun providerEntry(root: Map<*, *>, providerId: String): Map<String, Any?> =
        map(map(root["provider"])[providerId])

    // -------------------------------------------------------------------- tests

    @Test
    fun build_produces_parseable_json_with_schema_model_and_provider() {
        val p = provider(ProviderType.OPENAI, "https://api.openai.com/v1")
        val json = OpenCodeConfigSync.build(p, model(p.id, "gpt-4o-mini"), "sk-test-123")

        val root = parse(json) // throws (→ test fails) on malformed JSON
        assertEquals(OpenCodeConfigSync.SCHEMA, root["\$schema"])
        assertEquals("prov-openai/gpt-4o-mini", root["model"])

        val entry = providerEntry(root, "prov-openai")
        assertEquals("@ai-sdk/openai-compatible", entry["npm"])
        assertEquals(p.name, entry["name"])

        val options = map(entry["options"])
        assertEquals("https://api.openai.com/v1", options["baseURL"])
        assertEquals("sk-test-123", options["apiKey"])

        val models = map(entry["models"])
        assertEquals("My Model", map(models["gpt-4o-mini"])["name"])
    }

    @Test
    fun npm_mapping_is_correct_per_provider_type() {
        assertEquals("@ai-sdk/openai-compatible", OpenCodeConfigSync.npmFor(ProviderType.OPENAI))
        assertEquals("@ai-sdk/openai-compatible", OpenCodeConfigSync.npmFor(ProviderType.OPENROUTER))
        assertEquals("@ai-sdk/openai-compatible", OpenCodeConfigSync.npmFor(ProviderType.CUSTOM_OPENAI))
        assertEquals("@ai-sdk/openai-compatible", OpenCodeConfigSync.npmFor(ProviderType.OLLAMA))
        assertEquals("@ai-sdk/anthropic", OpenCodeConfigSync.npmFor(ProviderType.ANTHROPIC))
        assertEquals("@ai-sdk/google", OpenCodeConfigSync.npmFor(ProviderType.GEMINI))
    }

    @Test
    fun anthropic_uses_ai_sdk_anthropic_and_keeps_base_url() {
        val root = parse(
            OpenCodeConfigSync.build(
                provider(ProviderType.ANTHROPIC, "https://api.anthropic.com"),
                model("prov-anthropic", "claude-3-5-sonnet-latest"),
                "sk-ant-test",
            )
        )
        assertEquals("prov-anthropic/claude-3-5-sonnet-latest", root["model"])
        val entry = providerEntry(root, "prov-anthropic")
        assertEquals("@ai-sdk/anthropic", entry["npm"])
        assertEquals("https://api.anthropic.com", map(entry["options"])["baseURL"])
    }

    @Test
    fun gemini_uses_ai_sdk_google_and_keeps_base_url() {
        val root = parse(
            OpenCodeConfigSync.build(
                provider(ProviderType.GEMINI, "https://generativelanguage.googleapis.com"),
                model("prov-gemini", "gemini-1.5-flash"),
                "g-key",
            )
        )
        assertEquals("prov-gemini/gemini-1.5-flash", root["model"])
        val entry = providerEntry(root, "prov-gemini")
        assertEquals("@ai-sdk/google", entry["npm"])
        assertEquals(
            "https://generativelanguage.googleapis.com",
            map(entry["options"])["baseURL"],
        )
    }

    @Test
    fun ollama_base_url_gets_v1_suffix_appended() {
        val root = parse(
            OpenCodeConfigSync.build(
                provider(ProviderType.OLLAMA, "http://192.168.1.10:11434"),
                model("prov-ollama", "llama3.1:8b"),
                "",
            )
        )
        assertEquals("prov-ollama/llama3.1:8b", root["model"])
        val entry = providerEntry(root, "prov-ollama")
        assertEquals("@ai-sdk/openai-compatible", entry["npm"])
        assertEquals("http://192.168.1.10:11434/v1", map(entry["options"])["baseURL"])
    }

    @Test
    fun ollama_base_url_does_not_double_the_v1_suffix() {
        val root = parse(
            OpenCodeConfigSync.build(
                provider(ProviderType.OLLAMA, "http://127.0.0.1:11434/v1/"),
                model("prov-ollama", "qwen2.5:7b"),
                "",
            )
        )
        assertEquals("http://127.0.0.1:11434/v1", map(providerEntry(root, "prov-ollama")["options"])["baseURL"])
    }

    @Test
    fun json_escapes_quotes_and_backslashes_in_values() {
        val json = OpenCodeConfigSync.build(
            provider(ProviderType.CUSTOM_OPENAI, "http://host\\path"),
            model("prov-custom_openai", "weird\"model"),
            "key\"with\\quotes",
        )
        val root = parse(json) // parseable ⇔ escaping is correct
        val options = map(providerEntry(root, "prov-custom_openai")["options"])
        assertEquals("http://host\\path", options["baseURL"])
        assertEquals("key\"with\\quotes", options["apiKey"])
        assertEquals("prov-custom_openai/weird\"model", root["model"])
    }

    @Test
    fun config_never_contains_the_default_heredoc_delimiter_line() {
        val json = OpenCodeConfigSync.build(
            provider(ProviderType.OPENAI, "https://api.openai.com/v1"),
            model("prov-openai", "gpt-4o-mini"),
            "k",
        )
        assertFalse(OpenCodeConfigSync.containsLine(json, "OCEOF"))
    }
}
