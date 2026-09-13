package com.openchat.android.ai.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the <think> stream filter (reasoning-model output hiding). */
class ThinkFilterTest {

    private fun run(vararg chunks: String, finish: Boolean = true): String {
        val out = StringBuilder()
        val f = ThinkFilter { out.append(it) }
        for (c in chunks) f.feed(c)
        if (finish) f.finish()
        return out.toString()
    }

    // ------------------------------------------------------- non-thinking

    @Test
    fun `plain output passes through untouched`() {
        assertEquals("Hello world!", run("Hello ", "world!"))
    }

    @Test
    fun `output that merely contains angle brackets is not touched`() {
        assertEquals("use <div> for markup", run("use ", "<div> for markup"))
    }

    @Test
    fun `split lookalike tag passes through literally`() {
        assertEquals("a <thi nks b", run("a ", "<thi", " nks b"))
    }

    @Test
    fun `leading whitespace without tag is preserved at finish`() {
        assertEquals("  hi", run("  ", "hi"))
    }

    // ---------------------------------------------------------- thinking

    @Test
    fun `simple think block is hidden`() {
        assertEquals("Final answer", run("<think>step 1, step 2</think>Final answer"))
    }

    @Test
    fun `newline after close tag is dropped`() {
        assertEquals("Answer", run("<think>\nreasoning here\n</think>\n\nAnswer"))
    }

    @Test
    fun `tags split across chunks are still hidden`() {
        assertEquals("Answer", run("<th", "ink>reason", "</th", "ink>An", "swer"))
    }

    @Test
    fun `open tag split across chunks hides thinking`() {
        assertEquals("Answer", run("<t", "hink>r", "</think>", "Answer"))
    }

    @Test
    fun `leading whitespace before think tag is dropped`() {
        assertEquals("Answer", run("  ", "<think>r</think>", "Answer"))
    }

    @Test
    fun `empty think block yields just the answer`() {
        assertEquals("Answer", run("<think></think>Answer"))
    }

    @Test
    fun `only answer chunk after empty think`() {
        assertEquals("A", run("<think>", "</think>", "A"))
    }

    // ---------------------------------------------- unclosed (budget ran out)

    @Test
    fun `unclosed think flushes buffered thinking at finish`() {
        assertEquals("partial reasoning", run("<think>partial reasoning", "</th"))
    }

    @Test
    fun `stream ending inside an ambiguous tag prefix flushes it literally`() {
        // Honest pass-through: a non-thinking model that stops right after
        // "<th" must still show its (garbage) output, not a silent empty chat.
        assertEquals("<th", run("<th"))
    }

    @Test
    fun `thinking buffer is capped`() {
        val out = StringBuilder()
        val f = ThinkFilter { out.append(it) }
        f.feed("<think>" + "x".repeat(20_000))
        f.finish()
        assertEquals(8192, out.length)
    }

    // ------------------------------------------------------------- cancelled

    @Test
    fun `no finish call keeps hidden thinking hidden`() {
        assertEquals("", run("<think>secret", finish = false))
    }
}
