package com.openchat.android.ai.providers

/**
 * Pure, dependency-free SSE / JSON delta parsing helpers (spec §32 — no mocks, real parsing).
 *
 * These functions are deliberately implemented WITHOUT org.json so that they are
 * unit-testable on the plain JVM (the Android Gradle unit-test classpath only
 * provides stubbed org.json classes). The production SSE clients use them to
 * extract text deltas from provider payloads.
 */
object Sse {

    /**
     * Extracts the payload of every `data:` line from a raw SSE chunk.
     *
     * Robust to blank lines (event separators), `event:`/`id:`/`retry:` fields,
     * and `:` comment lines. Removes exactly one leading space after the colon
     * as required by the SSE specification. Handles CRLF line endings.
     */
    fun parseSseDataLines(raw: String): List<String> {
        val out = ArrayList<String>()
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) continue            // event separator
            if (line.startsWith(":")) continue      // SSE comment / keep-alive
            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            if (field != "data") continue           // ignore event:, id:, retry:
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            out.add(value)
        }
        return out
    }

    /**
     * OpenAI-compatible delta: `choices[0].delta.content`.
     * Returns null when the chunk carries no content ([DONE] sentinel, role-only
     * chunk, or malformed JSON → skipped by the caller).
     */
    fun parseOpenAIDelta(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val choices = root["choices"] as? List<*> ?: return null
        val first = choices.firstOrNull() as? Map<*, *> ?: return null
        val delta = first["delta"] as? Map<*, *> ?: return null
        delta["content"] as? String
    }.getOrNull()

    // Contract-named aliases (INTEGRATION.md test contract: SseChunkParseTest
    // tests splitSseData + openAIDelta; the parse* names are the canonical impls).

    /** Contract alias for [parseSseDataLines]: payloads after every `data:` line. */
    fun splitSseData(raw: String): List<String> = parseSseDataLines(raw)

    /** Contract alias for [parseOpenAIDelta]: null on malformed/empty payload. */
    fun openAIDelta(payload: String): String? = parseOpenAIDelta(payload)

    /** Anthropic delta text for `content_block_delta` events; null for other events. */
    fun parseAnthropicDelta(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        if (root["type"] != "content_block_delta") return null
        val delta = root["delta"] as? Map<*, *> ?: return null
        delta["text"] as? String
    }.getOrNull()

    /** True when the Anthropic SSE event is `message_stop` (stream finished). */
    fun parseAnthropicStop(json: String): Boolean = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return false
        root["type"] == "message_stop"
    }.getOrDefault(false)

    /**
     * Human-readable message of an Anthropic `error` SSE event (type=="error",
     * payload `error.message`), or null when the chunk is not an error.
     */
    fun parseAnthropicError(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        if (root["type"] != "error") return null
        val err = root["error"] as? Map<*, *> ?: return null
        err["message"] as? String
    }.getOrNull()

    /**
     * Gemini delta: concatenation of `candidates[0].content.parts[*].text`.
     * Returns null when the chunk carries no text or is malformed.
     */
    fun parseGeminiDelta(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val candidates = root["candidates"] as? List<*> ?: return null
        val first = candidates.firstOrNull() as? Map<*, *> ?: return null
        val content = first["content"] as? Map<*, *> ?: return null
        val parts = content["parts"] as? List<*> ?: return null
        val text = parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
        text.ifEmpty { null }
    }.getOrNull()

    /** Message of a Gemini error payload (`error.message`), or null when not an error. */
    fun parseGeminiError(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val err = root["error"] as? Map<*, *> ?: return null
        err["message"] as? String
    }.getOrNull()

    /**
     * Ollama `/api/chat` delta: `message.content`.
     * Returns null for the final `done:true` line or malformed JSON.
     */
    fun parseOllamaChatDelta(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val message = root["message"] as? Map<*, *> ?: return null
        message["content"] as? String
    }.getOrNull()

    /** True when an Ollama streaming line signals completion (`done: true`). */
    fun parseOllamaDone(json: String): Boolean = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return false
        root["done"] == true
    }.getOrDefault(false)

    /** Message of an Ollama streaming error line (`error` field), or null. */
    fun parseOllamaError(json: String): String? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        root["error"] as? String
    }.getOrNull()
}

/**
 * Minimal recursive-descent JSON reader (RFC 8259 subset) used by [Sse] and by
 * unit tests to assert JSON structure without the Android stub jar.
 *
 * Maps → [LinkedHashMap], arrays → [ArrayList], strings → [String],
 * numbers → [Double], booleans → [Boolean], null → null.
 * Malformed input throws [IllegalArgumentException].
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw p.error("Trailing characters after JSON value")
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun error(msg: String): IllegalArgumentException =
            IllegalArgumentException("MiniJson: $msg at offset $i")

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (atEnd()) throw error("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else throw error("Unexpected character '$c'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // consume '{'
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') throw error("Expected object key string")
                val key = parseString()
                skipWs()
                if (atEnd() || s[i] != ':') throw error("Expected ':' after object key")
                i++
                out[key] = parseValue()
                skipWs()
                if (atEnd()) throw error("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw error("Expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // consume '['
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (atEnd()) throw error("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw error("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            i++ // consume opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("Unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd()) throw error("Unterminated escape sequence")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C') // form feed (\u000C — Kotlin has no \f escape)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw error("Invalid \\u escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: throw error("Invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw error("Invalid escape '\\$e'")
                        }
                        i++
                    }
                    else -> {
                        if (c.code < 0x20) throw error("Unescaped control character in string")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (s.regionMatches(i, literal, 0, literal.length)) {
                i += literal.length
                return value
            }
            throw error("Invalid literal, expected '$literal'")
        }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val token = s.substring(start, i)
            return token.toDoubleOrNull() ?: throw error("Invalid number '$token'")
        }
    }
}
