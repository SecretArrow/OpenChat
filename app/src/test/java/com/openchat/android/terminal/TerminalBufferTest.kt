package com.openchat.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the real VT/ANSI parser (no Android imports — the buffer
 * must stay framework-free so CI can run these without a device).
 */
class TerminalBufferTest {

    @Test
    fun `feed text with CRLF prints the line and moves to the next row`() {
        val b = TerminalBuffer()
        b.feed("hello\r\n".toByteArray())
        assertEquals("hello", b.lineText(0))
        assertEquals(1, b.cursorRow())
        assertEquals(0, b.cursorCol())
    }

    @Test
    fun `multiple lines land on separate rows`() {
        val b = TerminalBuffer()
        b.feed("$ ls\r\ndir1\r\n".toByteArray())
        assertEquals("$ ls", b.lineText(0))
        assertEquals("dir1", b.lineText(1))
    }

    @Test
    fun `SGR foreground color is packed into the cell attributes`() {
        val b = TerminalBuffer()
        b.feed("\u001B[31mX\u001B[0m".toByteArray())
        val attr = b.lineAttrs(0)[0]
        assertTrue("expected a non-default foreground, got $attr", (attr and 0x1FF) != 0)
        // SGR 31 → palette index 2 (fg occupies bits 0..8)
        assertEquals(2, attr and 0x1FF)
    }

    @Test
    fun `SGR bold sets bit 18 and reset clears it`() {
        val b = TerminalBuffer()
        b.feed("\u001B[1mA".toByteArray())
        assertTrue((b.lineAttrs(0)[0] and (1 shl 18)) != 0)
        b.feed("\u001B[0mB".toByteArray())
        assertEquals(0, b.lineAttrs(0)[1] and (1 shl 18))
    }

    @Test
    fun `SGR 256-color encodes index plus 17 in the fg bits`() {
        val b = TerminalBuffer()
        b.feed("\u001B[38;5;196mZ".toByteArray())
        assertEquals(196 + 17, b.lineAttrs(0)[0] and 0x1FF)
    }

    @Test
    fun `background color goes to the bg bits 9 to 17`() {
        val b = TerminalBuffer()
        b.feed("\u001B[44mQ".toByteArray())
        assertEquals(5, (b.lineAttrs(0)[0] shr 9) and 0x1FF)
    }

    @Test
    fun `ESC 2J clears the whole screen`() {
        val b = TerminalBuffer()
        b.feed("junk\r\nmore junk".toByteArray())
        b.feed("\u001B[2J".toByteArray())
        assertEquals("", b.lineText(0))
        assertEquals("", b.lineText(1))
    }

    @Test
    fun `ESC K erases from the cursor to end of line`() {
        val b = TerminalBuffer()
        b.feed("abcdef".toByteArray())
        b.feed("\r\u001B[3K".toByteArray())
        assertEquals("", b.lineText(0))
    }

    @Test
    fun `CUP positions the cursor one-based`() {
        val b = TerminalBuffer()
        b.feed("\u001B[3;5H".toByteArray())
        assertEquals(2, b.cursorRow())
        assertEquals(4, b.cursorCol())
    }

    @Test
    fun `OSC sequences are swallowed until BEL`() {
        val b = TerminalBuffer()
        b.feed("\u001B]0;some title\u0007".toByteArray())
        b.feed("ok".toByteArray())
        assertEquals("ok", b.lineText(0))
    }

    @Test
    fun `OSC sequences are swallowed until ST`() {
        val b = TerminalBuffer()
        b.feed("\u001B]2;window title\u001B\\".toByteArray())
        b.feed("ok".toByteArray())
        assertEquals("ok", b.lineText(0))
    }

    @Test
    fun `charset designator ESC B is swallowed`() {
        val b = TerminalBuffer()
        b.feed("\u001B(B".toByteArray())
        b.feed("ok".toByteArray())
        assertEquals("ok", b.lineText(0))
    }

    @Test
    fun `tab advances to the next 8-column stop`() {
        val b = TerminalBuffer()
        b.feed("a\tb".toByteArray())
        assertEquals('a', b.lineText(0)[0])
        assertEquals('b', b.lineText(0)[8])
    }

    @Test
    fun `backspace moves the cursor left and the next char overwrites`() {
        val b = TerminalBuffer()
        b.feed("ab\u0008d".toByteArray())
        assertEquals("ad", b.lineText(0))
    }

    @Test
    fun `long lines word wrap at the column limit`() {
        val b = TerminalBuffer(80, 24)
        val line = "x".repeat(90)
        b.feed(line.toByteArray())
        assertEquals(80, b.lineText(0).length)
        assertEquals(10, b.lineText(1).length)
        assertEquals(line, b.lineText(0) + b.lineText(1))
    }

    @Test
    fun `scrolling past the bottom pushes lines into the scrollback ring`() {
        val b = TerminalBuffer(80, 24)
        val data = (0 until 40).joinToString("\n") { "L$it" } // no trailing newline
        b.feed(data.toByteArray())
        assertEquals(16, b.scrollbackCount())
        assertEquals("L0", b.scrollbackLine(0))
        assertEquals("L15", b.scrollbackLine(15))
        assertEquals("L16", b.lineText(0))
        assertEquals("L39", b.lineText(23))
        assertEquals(23, b.cursorRow())
    }

    @Test
    fun `scrollback ring respects its limit`() {
        val b = TerminalBuffer(80, 4, scrollbackLimit = 10)
        val data = (0 until 50).joinToString("\n") { "L$it" }
        b.feed(data.toByteArray())
        assertEquals(10, b.scrollbackCount())
        assertTrue(b.scrollbackLine(0).startsWith("L"))
        assertTrue(b.scrollbackLine(9).startsWith("L"))
    }

    @Test
    fun `UTF-8 sequences split across feed calls decode correctly`() {
        val b = TerminalBuffer()
        b.feed(byteArrayOf('h'.code.toByte(), 0xC3.toByte()))
        b.feed(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()))
        assertEquals("héllo", b.lineText(0))
    }

    @Test
    fun `resize keeps content and reports the new geometry`() {
        val b = TerminalBuffer(80, 24)
        b.feed("hello".toByteArray())
        b.resize(40, 10)
        assertEquals(40, b.cols())
        assertEquals(10, b.rows())
        assertEquals("hello", b.lineText(0))
    }

    @Test
    fun `dumpPlain joins the visible screen with newlines`() {
        val b = TerminalBuffer(80, 3)
        b.feed("one\r\ntwo".toByteArray())
        assertEquals("one\ntwo\n", b.dumpPlain())
    }
}
