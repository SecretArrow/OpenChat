package com.openchat.android.ai.local

/**
 * Streams local-model output to the chat while hiding the `<think>…</think>`
 * reasoning phase emitted by thinking models (DeepSeek R1 distills, Qwen3,
 * SmolLM3). The JNI engine streams raw tokens; without this filter reasoning
 * models would paint raw tags into the chat.
 *
 * Honest behavior (spec §32):
 *  - non-thinking models: text passes through unchanged (the only buffering is
 *    the initial lookahead that decides whether output starts with `<think>` —
 *    at most a few characters, then everything streams directly).
 *  - thinking models: only the final answer is streamed. If generation ends
 *    while still inside the thinking phase (e.g. the token budget ran out),
 *    the buffered thinking text is flushed so the user never gets a silent
 *    empty answer.
 *
 * Tags are handled even when they arrive split across stream chunks
 * (`"<th"` + `"ink>"`, `"</th"` + `"ink>"`).
 */
class ThinkFilter(private val onDelta: (String) -> Unit) {

    private enum class Phase { START, IN_THINK, STREAM }

    private var phase = Phase.START

    /** START: text that may still turn out to be "<think>". */
    private var pending = StringBuilder()

    /** IN_THINK: tail that may still turn out to be "</think>". */
    private val work = StringBuilder()

    /** Thinking text, capped — flushed by [finish] if the tag never closes. */
    private val thinkBuf = StringBuilder()

    /** Max thinking chars kept for the unclosed-at-end fallback. */
    private val thinkCap = 8192

    fun feed(piece: String) {
        if (piece.isEmpty()) return
        when (phase) {
            Phase.START -> feedStart(piece)
            Phase.IN_THINK -> feedThink(piece)
            Phase.STREAM -> onDelta(piece)
        }
    }

    /** Call once after generation ends; flushes anything still held back. */
    fun finish() {
        when (phase) {
            Phase.START -> {
                val out = pending.toString()
                pending = StringBuilder()
                if (out.isNotEmpty()) onDelta(out)
            }
            Phase.IN_THINK -> {
                // Token budget ran out mid-thinking: surface what was thought.
                val out = thinkBuf.toString()
                thinkBuf.setLength(0)
                if (out.isNotEmpty()) onDelta(out)
            }
            Phase.STREAM -> Unit
        }
    }

    // ------------------------------------------------------------------ internals

    private fun feedStart(piece: String) {
        pending.append(piece)
        val p = pending.toString()
        val body = p.trimStart()
        if (body.length < OPEN.length && OPEN.startsWith(body)) {
            return // still ambiguous (e.g. "  ", "  <th") — keep buffering
        }
        if (body.startsWith(OPEN)) {
            phase = Phase.IN_THINK
            pending = StringBuilder()
            val rest = body.substring(OPEN.length)
            if (rest.isNotEmpty()) feedThink(rest)
            return
        }
        // Not a thinking model: everything buffered is visible text.
        phase = Phase.STREAM
        pending = StringBuilder()
        onDelta(p)
    }

    private fun feedThink(piece: String) {
        work.append(piece)
        val idx = work.indexOf(CLOSE)
        if (idx >= 0) {
            appendThink(work.substring(0, idx))
            var rest = work.substring(idx + CLOSE.length)
            work.setLength(0)
            rest = rest.trimStart() // drop the newline(s) right after the tag
            phase = Phase.STREAM
            if (rest.isNotEmpty()) onDelta(rest)
            return
        }
        // No close tag yet: emit the safe head, keep a possible partial tag.
        val keep = partialCloseLen(work.toString())
        if (work.length > keep) {
            appendThink(work.substring(0, work.length - keep))
            val tail = work.substring(work.length - keep)
            work.setLength(0)
            work.append(tail)
        }
    }

    private fun appendThink(text: String) {
        var t = text
        if (thinkBuf.isEmpty()) t = t.trimStart()
        if (t.isEmpty()) return
        val room = thinkCap - thinkBuf.length
        if (room <= 0) return
        thinkBuf.append(if (t.length > room) t.substring(0, room) else t)
    }

    /** Longest suffix of [s] that is a proper prefix of [CLOSE]. */
    private fun partialCloseLen(s: String): Int {
        val max = minOf(CLOSE.length - 1, s.length)
        for (len in max downTo 1) {
            if (CLOSE.startsWith(s.substring(s.length - len))) return len
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
