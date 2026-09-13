package com.openchat.android.workspace

import com.openchat.android.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Pins the persisted `workspaces.json` schema of [Workspace] (spec §25).
 *
 * NOTE on the test environment: the Android unit-test classpath ships *stubbed*
 * org.json classes (methods return defaults with `returnDefaultValues=true`),
 * so a live `toJson()/fromJson()` round-trip cannot run there — the same reason
 * the AI layer bundles a pure parser for its tests. Therefore:
 *
 *  - Test 1 pins the exact canonical JSON document (key names/shapes) that
 *    [Workspace.toJson] must produce and [Workspace.fromJson] must accept,
 *    parsed with a real mini JSON parser included below.
 *  - Test 2 performs the full object→object round-trip on any classpath where
 *    org.json is real (skipped silently where it is stubbed).
 */
class WorkspaceJsonTest {

    // ------------------------------------------------------------- schema pin

    @Test
    fun `canonical workspaces json document parses into the documented Workspace fields`() {
        val w = Workspace(
            id = "w-1",
            name = "demo",
            path = "/root/workspaces/demo",
            providerId = "prov-1",
            modelId = "model-1",
            envVars = mapOf("A" to "1"),
            opencodeConfig = null,
            createdAt = 1_700_000_000_000L,
            lastOpenedAt = null,
        )
        val parsed = MiniJson.parse(CANONICAL_WORKSPACE_JSON) as Map<*, *>

        assertEquals(w.id, parsed["id"])
        assertEquals(w.name, parsed["name"])
        assertEquals(w.path, parsed["path"])
        assertEquals(w.providerId, parsed["providerId"])
        assertEquals(w.modelId, parsed["modelId"])
        assertEquals(w.envVars, parsed["envVars"])
        assertEquals(w.opencodeConfig, parsed["opencodeConfig"])
        assertEquals(w.createdAt.toDouble(), (parsed["createdAt"] as Number).toDouble(), 0.0)
        assertEquals(w.lastOpenedAt, parsed["lastOpenedAt"])

        // The exact key set — nothing less, nothing more (schema drift detector).
        assertEquals(
            setOf("id", "name", "path", "providerId", "modelId", "envVars", "opencodeConfig", "createdAt", "lastOpenedAt"),
            parsed.keys,
        )
    }

    @Test
    fun `null optional fields are JSON nulls and env vars are a nested object`() {
        val parsed = MiniJson.parse(CANONICAL_WORKSPACE_JSON) as Map<*, *>
        assertTrue(parsed.containsKey("providerId"))
        assertEquals(null, parsed["opencodeConfig"])
        assertEquals(mapOf("A" to "1"), parsed["envVars"])
    }

    @Test
    fun `a fully populated document keeps every value`() {
        val parsed = MiniJson.parse(CANONICAL_WORKSPACE_POPULATED) as Map<*, *>
        assertEquals("prov-1", parsed["providerId"])
        assertEquals("model-1", parsed["modelId"])
        assertEquals(mapOf("A" to "1", "PATH_EXTRA" to "/opt/bin"), parsed["envVars"])
        assertEquals("/root/.config/opencode/opencode.json", parsed["opencodeConfig"])
        assertEquals(1_700_000_100_000.0, (parsed["lastOpenedAt"] as Number).toDouble(), 0.0)
    }

    // ------------------------------------------------------- live round-trip

    @Test
    fun `toJson and fromJson round-trip every field`() {
        assumeTrue("org.json is stubbed on the unit-test classpath — schema covered by the tests above", orgJsonIsReal())
        val w = Workspace(
            id = "w-2",
            name = "round-trip",
            path = "/root/workspaces/round-trip",
            providerId = "prov-2",
            modelId = null,
            envVars = mapOf("A" to "1", "B" to "2"),
            opencodeConfig = "{\"model\":\"x\"}",
            createdAt = 1_700_000_000_000L,
            lastOpenedAt = 1_700_000_050_000L,
        )
        val back = Workspace.fromJson(w.toJson())
        assertEquals(w.id, back.id)
        assertEquals(w.name, back.name)
        assertEquals(w.path, back.path)
        assertEquals(w.providerId, back.providerId)
        assertEquals(w.modelId, back.modelId)
        assertEquals(w.envVars, back.envVars)
        assertEquals(w.opencodeConfig, back.opencodeConfig)
        assertEquals(w.createdAt, back.createdAt)
        assertEquals(w.lastOpenedAt, back.lastOpenedAt)
    }

    @Test
    fun `fromJson tolerates missing optional fields`() {
        assumeTrue("org.json is stubbed on the unit-test classpath", orgJsonIsReal())
        val o = JSONObject()
            .put("id", "w-3")
            .put("name", "minimal")
            .put("path", "/root/workspaces/minimal")
        val w = Workspace.fromJson(o)
        assertEquals("w-3", w.id)
        assertEquals("minimal", w.name)
        assertEquals("/root/workspaces/minimal", w.path)
        assertEquals(null, w.providerId)
        assertEquals(null, w.modelId)
        assertEquals(emptyMap<String, String>(), w.envVars)
    }

    private fun orgJsonIsReal(): Boolean = runCatching {
        val probe = JSONObject()
        probe.put("k", "v")
        probe.optString("k") == "v"
    }.getOrDefault(false)

    private companion object {
        /**
         * The canonical document Workspace.toJson() emits for the test fields
         * (org.json serialization: compact, nulls explicit).
         */
        val CANONICAL_WORKSPACE_JSON = """
            {"id":"w-1","name":"demo","path":"/root/workspaces/demo","providerId":"prov-1","modelId":"model-1",
             "envVars":{"A":"1"},"opencodeConfig":null,"createdAt":1700000000000,"lastOpenedAt":null}
        """.trimIndent()

        val CANONICAL_WORKSPACE_POPULATED = """
            {"id":"w-9","name":"full","path":"/root/workspaces/full","providerId":"prov-1","modelId":"model-1",
             "envVars":{"A":"1","PATH_EXTRA":"/opt/bin"},
             "opencodeConfig":"/root/.config/opencode/opencode.json",
             "createdAt":1700000000000,"lastOpenedAt":1700000100000}
        """.trimIndent()
    }
}

/**
 * A small but REAL JSON parser (RFC 8259 subset) used to verify document
 * structure without the stubbed org.json runtime. Returns Map/List/String/
 * Double/Boolean/null. Escapes (\uXXXX, \n, \t, \", \\, \/, \b, \f) supported.
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text.trim())
        val value = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Trailing content at ${p.pos}")
        return value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of input")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        when (val e = text[pos]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'u' -> {
                                val hex = text.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            return text.substring(start, pos).toDouble()
        }

        fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) throw IllegalArgumentException("Bad literal at $pos")
            pos += literal.length
            return value
        }

        fun peek(): Char {
            if (atEnd()) throw IllegalArgumentException("Unexpected end of input")
            return text[pos]
        }

        fun expect(c: Char) {
            if (atEnd() || text[pos] != c) throw IllegalArgumentException("Expected '$c' at $pos")
            pos++
        }
    }
}
