package com.openchat.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bundled provider-presets catalog (assets/provider_presets.json v2):
 * parsing tolerates bad entries, protocol resolution follows the documented
 * precedence, and preset → Provider mapping lands on the correct transport
 * (openai wire → CUSTOM_OPENAI / OPENROUTER, anthropic wire → ANTHROPIC).
 */
class ProviderPresetsTest {

    private val catalog = """
    {
      "version": 2,
      "providers": [
        {"id":"agentrouter","name":"AgentRouter","protocols":["anthropic","openai"],"defaultProtocol":"auto",
         "connections":{
           "anthropic":{"baseUrl":"https://agentrouter.org/v1","auth":{"type":"api_key","header":"x-api-key"},
             "endpoints":{"chat":"/messages","models":"/models"}},
           "openai":{"baseUrl":"https://agentrouter.org/v1","auth":{"type":"bearer","header":"Authorization"},
             "endpoints":{"chat":"/chat/completions","responses":"/responses","models":"/models"}}},
         "discovery":{"enabled":true,"protocol":"auto"}},
        {"id":"nvidia-nim","name":"NVIDIA NIM","protocols":["openai"],"defaultProtocol":"openai",
         "connections":{"openai":{"baseUrl":"https://integrate.api.nvidia.com/v1",
           "auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"chat":"/chat/completions","models":"/models"}}},
         "discovery":{"enabled":true,"protocol":"openai"}},
        {"id":"openrouter","name":"OpenRouter","protocols":["openai","anthropic"],"defaultProtocol":"openai",
         "connections":{
           "openai":{"baseUrl":"https://openrouter.ai/api/v1","auth":{"type":"bearer","header":"Authorization"},
             "endpoints":{"chat":"/chat/completions","responses":"/responses","models":"/models"}},
           "anthropic":{"baseUrl":"https://openrouter.ai","auth":{"type":"api_key","header":"x-api-key"},
             "endpoints":{"chat":"/api/v1/messages","models":"/api/v1/models"}}},
         "discovery":{"enabled":true,"protocol":"openai"}},
        {"id":"poolside","name":"Poolside","protocols":["auto"],"defaultProtocol":"auto",
         "connections":{"auto":{"baseUrl":"","auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"models":"/models"},"customBaseUrl":true}},
         "discovery":{"enabled":true,"protocol":"auto"}},
        {"id":"commandcode","name":"CommandCode","protocols":["auto"],"defaultProtocol":"auto",
         "connections":{"auto":{"baseUrl":"","auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"models":"/models"},"customBaseUrl":true}},
         "discovery":{"enabled":true,"protocol":"auto"}},
        {"id":"nararouter","name":"NaraRouter","protocols":["openai","anthropic"],"defaultProtocol":"auto",
         "connections":{
           "openai":{"baseUrl":"https://router.bynara.id/v1","auth":{"type":"bearer","header":"Authorization"},
             "endpoints":{"chat":"/chat/completions","responses":"/responses","models":"/models"}},
           "anthropic":{"baseUrl":"https://router.bynara.id","auth":{"type":"api_key","header":"x-api-key"},
             "endpoints":{"chat":"/v1/messages","models":"/v1/models"}}},
         "discovery":{"enabled":true,"protocol":"auto"}},
        {"id":"zyloo","name":"Zyloo","protocols":["openai"],"defaultProtocol":"openai",
         "connections":{"openai":{"baseUrl":"https://api.zyloo.io/v1","auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"chat":"/chat/completions","responses":"/responses","models":"/models"}}},
         "discovery":{"enabled":true,"protocol":"openai"}},
        {"id":"bigmodel","name":"BigModel / Zhipu","protocols":["openai"],"defaultProtocol":"openai",
         "connections":{"openai":{"baseUrl":"https://open.bigmodel.cn/api/paas/v4","auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"chat":"/chat/completions","models":"/models"}}},
         "discovery":{"enabled":true,"protocol":"openai"}},
        {"id":"tokenrouter","name":"TokenRouter","protocols":["auto"],"defaultProtocol":"auto",
         "connections":{"auto":{"baseUrl":"https://api.tokenrouter.com/v1","auth":{"type":"bearer","header":"Authorization"},
           "endpoints":{"models":"/models"}}},
         "discovery":{"enabled":true,"protocol":"auto"}}
      ]
    }
    """.trimIndent()

    private fun byId(id: String): ProviderPreset =
        ProviderPresets.parse(catalog).first { it.id == id }

    @Test
    fun `all nine bundled presets parse in catalog order`() {
        val presets = ProviderPresets.parse(catalog)
        assertEquals(9, presets.size)
        assertEquals(
            listOf(
                "agentrouter", "nvidia-nim", "openrouter", "poolside", "commandcode",
                "nararouter", "zyloo", "bigmodel", "tokenrouter",
            ),
            presets.map { it.id },
        )
        assertTrue(presets.all { it.discoveryEnabled })
    }

    @Test
    fun `multi-protocol presets expose both wire formats`() {
        assertTrue(byId("agentrouter").isMultiProtocol())
        assertTrue(byId("openrouter").isMultiProtocol())
        assertTrue(byId("nararouter").isMultiProtocol())
        assertFalse(byId("nvidia-nim").isMultiProtocol())
        assertFalse(byId("bigmodel").isMultiProtocol())
    }

    @Test
    fun `custom-base-url presets carry an empty baseUrl`() {
        val poolside = byId("poolside")
        assertTrue(poolside.connection("auto")!!.customBaseUrl)
        assertEquals("", poolside.connection("auto")!!.baseUrl)

        // TokenRouter ships a concrete URL despite the "auto" protocol.
        assertEquals("https://api.tokenrouter.com/v1", byId("tokenrouter").connection("auto")!!.baseUrl)
    }

    @Test
    fun `protocol resolution prefers openai when default is auto`() {
        assertEquals("openai", PresetMapper.resolveProtocol(byId("agentrouter"), null))
        assertEquals("openai", PresetMapper.resolveProtocol(byId("nararouter"), null))
        assertEquals("openai", PresetMapper.resolveProtocol(byId("openrouter"), null))
        // Explicit defaults win when concrete.
        assertEquals("openai", PresetMapper.resolveProtocol(byId("nvidia-nim"), null))
        // Single "auto" connection stays "auto".
        assertEquals("auto", PresetMapper.resolveProtocol(byId("poolside"), null))
        // Explicit preferred protocol wins when the preset has it.
        assertEquals("anthropic", PresetMapper.resolveProtocol(byId("agentrouter"), "anthropic"))
        // Unknown preferred falls back gracefully.
        assertEquals("openai", PresetMapper.resolveProtocol(byId("agentrouter"), "grpc"))
    }

    @Test
    fun `preset to provider maps to the correct transports`() {
        assertEquals(ProviderType.CUSTOM_OPENAI, PresetMapper.providerType(byId("agentrouter"), "openai"))
        assertEquals(ProviderType.ANTHROPIC, PresetMapper.providerType(byId("agentrouter"), "anthropic"))
        assertEquals(ProviderType.OPENROUTER, PresetMapper.providerType(byId("openrouter"), "openai"))
        assertEquals(ProviderType.ANTHROPIC, PresetMapper.providerType(byId("openrouter"), "anthropic"))
        assertEquals(ProviderType.ANTHROPIC, PresetMapper.providerType(byId("nararouter"), "anthropic"))
        assertEquals(ProviderType.CUSTOM_OPENAI, PresetMapper.providerType(byId("poolside"), "auto"))
        assertEquals(ProviderType.CUSTOM_OPENAI, PresetMapper.providerType(byId("nvidia-nim"), "openai"))

        val p = PresetMapper.toProvider(byId("nvidia-nim"), null)
        assertEquals("NVIDIA NIM", p.name)
        assertEquals("https://integrate.api.nvidia.com/v1", p.baseUrl)
        assertEquals(ProviderType.CUSTOM_OPENAI, p.type)
    }

    @Test
    fun `model discovery paths follow the preset endpoints`() {
        assertEquals("/models", PresetMapper.modelsPath(byId("agentrouter"), "openai"))
        assertEquals("/models", PresetMapper.modelsPath(byId("agentrouter"), "anthropic"))
        assertEquals("/api/v1/models", PresetMapper.modelsPath(byId("openrouter"), "anthropic"))
        assertEquals("/v1/models", PresetMapper.modelsPath(byId("nararouter"), "anthropic"))
        // Fallback when endpoints omit "models": wire-format default.
        val noModels = ProviderPreset(
            id = "x", name = "X", protocols = listOf("anthropic"), defaultProtocol = "anthropic",
            connections = mapOf(
                "anthropic" to ProviderPreset.PresetConnection(
                    baseUrl = "https://x", authType = "api_key", authHeader = "x-api-key",
                )
            ),
        )
        assertEquals("/v1/models", PresetMapper.modelsPath(noModels, "anthropic"))
    }

    @Test
    fun `auth style maps per connection`() {
        assertTrue(PresetMapper.usesApiKeyHeader(byId("agentrouter"), "anthropic"))
        assertEquals("x-api-key", PresetMapper.apiKeyHeaderName(byId("agentrouter"), "anthropic"))
        assertFalse(PresetMapper.usesApiKeyHeader(byId("agentrouter"), "openai"))
        assertFalse(PresetMapper.usesApiKeyHeader(byId("nararouter"), "openai"))
        assertTrue(PresetMapper.usesApiKeyHeader(byId("nararouter"), "anthropic"))
    }

    @Test
    fun `chat endpoints are preserved for reference`() {
        assertEquals("/chat/completions", PresetMapper.chatPath(byId("agentrouter"), "openai"))
        assertEquals("/messages", PresetMapper.chatPath(byId("agentrouter"), "anthropic"))
        assertEquals("/v1/messages", PresetMapper.chatPath(byId("nararouter"), "anthropic"))
        assertNull(PresetMapper.chatPath(byId("poolside"), "auto"))
    }

    @Test
    fun `malformed entries are skipped, garbage input yields empty list`() {
        val mixed = """
        {"providers":[
          {"id":"ok","name":"OK","protocols":["openai"],"defaultProtocol":"openai",
           "connections":{"openai":{"baseUrl":"https://ok","auth":{"type":"bearer","header":"Authorization"},
             "endpoints":{"models":"/models"}}}},
          {"name":"no-connections"},
          {"id":"bad-conn","name":"Bad","connections":{"openai":"not-an-object"}},
          "not-even-an-object"
        ]}
        """.trimIndent()
        val presets = ProviderPresets.parse(mixed)
        assertEquals(1, presets.size)
        assertEquals("ok", presets[0].id)

        assertEquals(emptyList<ProviderPreset>(), ProviderPresets.parse("not json at all"))
        assertEquals(emptyList<ProviderPreset>(), ProviderPresets.parse("{}"))
        assertEquals(emptyList<ProviderPreset>(), ProviderPresets.parse(""))
    }

    @Test
    fun `id falls back to slugified name and connection lookup is case-safe`() {
        val p = ProviderPresets.parse(
            """{"providers":[{"name":"My Custom Router","connections":{
              "OpenAI":{"baseUrl":"https://mcr","auth":{"type":"bearer","header":"Authorization"},
                "endpoints":{"models":"/models"}}}}]}"""
        )
        assertEquals(1, p.size)
        assertEquals("my-custom-router", p[0].id)
        assertNotNull(p[0].connection("openai"))
        assertNotNull(p[0].connection("OpenAI"))
        assertNull(p[0].connection("anthropic"))
    }

    @Test
    fun `the REAL bundled asset parses with all nine shipped presets`() {
        // Gradle JVM unit tests run with cwd = app/ — read the actual asset file.
        val f = java.io.File("src/main/assets/provider_presets.json")
        org.junit.Assume.assumeTrue("asset file not found (run from app/)", f.exists())
        val presets = ProviderPresets.parse(f.readText())
        assertEquals(9, presets.size)
        assertEquals("agentrouter", presets[0].id)
        assertEquals("tokenrouter", presets[8].id)
        assertTrue(presets.all { it.connections.isNotEmpty() })
        assertTrue(presets.all { it.name.isNotBlank() })
    }
}
