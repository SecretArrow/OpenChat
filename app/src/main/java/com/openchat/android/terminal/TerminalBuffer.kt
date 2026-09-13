package com.openchat.android.terminal

/**
 * Real VT100/ANSI terminal emulator backing the terminal UI (spec §15).
 *
 * Pure Kotlin — no Android imports, unit-testable on the JVM (TerminalBufferTest).
 * All public methods are @Synchronized: the session reader thread calls [feed]
 * while the UI thread reads lines on [com.openchat.android.terminal.TerminalSession.revision] changes.
 *
 * Features:
 *  - incremental UTF-8 decoding (partial multi-byte sequences are carried across feed() calls)
 *  - CSI: A/B/C/D cursor moves, H/f absolute position (1-based), J (0/1/2/3) erase display,
 *    K (0/1/2) erase line, m (SGR 0/1/3/7/22/23/27/30-37/39/40-47/49/90-97/100-107/38;5;n/48;5;n),
 *    G (column), d (row), s/u save/restore cursor, L/M insert/delete lines, X erase chars,
 *    private modes (?…h/l) are swallowed
 *  - ESC ] OSC sequences are swallowed until BEL or ESC \; ESC ( / ) charset designators swallowed
 *  - ESC M = reverse index, ESC E/D = line feed (+CR for E), ESC c = full reset, ESC 7/8 save/restore
 *  - \r \n \b \t BEL handling, word wrap at the column limit, scroll-up pushes the top
 *    line into a bounded scrollback ring (limit configurable, default 2000)
 *  - LF (\n) is handled as a newline (move down + carriage return) — the pty driver
 *    in ONLCR mode normally emits \r\n, and shells echo bare \n as new lines, so
 *    treating LF as CR+LF keeps columns from drifting (and matches the unit tests)
 *  - resize keeps the top of the screen anchored (content above the fold is never
 *    pushed to scrollback by a resize); the cursor is clamped into the new bounds
 *
 * Attribute packing per cell (see [lineAttrs]):
 *  - bits 0..8   foreground: 0 = default, 1..16 = 16-color palette (30-37 → 1..8, 90-97 → 9..16),
 *                17..272 = 256-color palette index + 17 (SGR 38;5;n)
 *  - bits 9..17  background (same encoding, 48;5;n / 40-47 / 100-107)
 *  - bit 18 bold, bit 19 italic, bit 20 reverse video (renderers swap fg/bg for reverse)
 */
class TerminalBuffer(initialCols: Int = 80, initialRows: Int = 24, scrollbackLimit: Int = 2000) {

    private class Line(val chars: CharArray, val attrs: IntArray)

    private enum class ParseState { GROUND, ESC, CSI, OSC, CHARSET }

    private val maxCols: Int = initialCols.coerceAtLeast(2)
    private val maxRows: Int = initialRows.coerceAtLeast(1)
    private val maxScrollback: Int = scrollbackLimit.coerceAtLeast(0)

    private var ncols: Int = maxCols
    private var nrows: Int = maxRows
    private var cursorRow: Int = 0
    private var cursorCol: Int = 0

    private var fg: Int = 0
    private var bg: Int = 0
    private var bold: Boolean = false
    private var italic: Boolean = false
    private var reverse: Boolean = false

    private var savedRow: Int = 0
    private var savedCol: Int = 0

    private val screen: ArrayList<Line> = ArrayList(maxRows)
    private val scrollback: kotlin.collections.ArrayDeque<Line> = kotlin.collections.ArrayDeque()

    private var carry: ByteArray = ByteArray(0)

    private var state: ParseState = ParseState.GROUND
    private val csiParams: ArrayList<Int> = ArrayList(8)
    private var csiCurrent: Int = 0
    private var csiHasCurrent: Boolean = false
    private var csiPrivate: Boolean = false
    private var oscEscSeen: Boolean = false

    init {
        repeat(nrows) { screen.add(blankLine(ncols)) }
    }

    // ------------------------------------------------------------------ public API

    /** Feeds raw pty output bytes into the emulator (UTF-8 + ANSI/VT escapes). */
    @Synchronized
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val all: ByteArray = if (carry.isEmpty()) bytes else carry + bytes
        var i = 0
        val n = all.size
        while (i < n) {
            val b = all[i].toInt() and 0xFF
            val len = utf8SequenceLen(b)
            if (len == -1) {
                // Invalid start byte (stray continuation or >= 0xF8): emit replacement.
                handleCodepoint(REPLACEMENT)
                i++
                continue
            }
            if (i + len > n) {
                // Incomplete multi-byte sequence — keep as carry for the next feed().
                carry = all.copyOfRange(i, n)
                return
            }
            var valid = true
            for (k in 1 until len) {
                val c = all[i + k].toInt() and 0xFF
                if (c < 0x80 || c > 0xBF) { valid = false; break }
            }
            if (!valid) {
                handleCodepoint(REPLACEMENT)
                i++
                continue
            }
            handleCodepoint(decodeUtf8(all, i, len))
            i += len
        }
        carry = ByteArray(0)
    }

    /**
     * Resizes the screen. Shrinks keep the TOP rows (content above the fold is
     * never lost into scrollback by a resize); the cursor is clamped.
     */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(1)
        if (c == ncols && r == nrows) return
        val next = ArrayList<Line>(r)
        if (r < nrows) {
            // Top-anchored shrink: keep rows 0..r-1 (the cursor is clamped; rows
            // below the new fold are dropped — they were off-screen content).
            for (i in 0 until r) next.add(resizeLine(screen[i], c))
        } else {
            for (line in screen) next.add(resizeLine(line, c))
            repeat(r - nrows) { next.add(blankLine(c)) }
        }
        screen.clear()
        screen.addAll(next)
        ncols = c
        nrows = r
        cursorRow = cursorRow.coerceIn(0, nrows - 1)
        cursorCol = cursorCol.coerceIn(0, ncols - 1)
    }

    @Synchronized fun rows(): Int = nrows

    @Synchronized fun cols(): Int = ncols

    @Synchronized fun cursorRow(): Int = cursorRow

    @Synchronized fun cursorCol(): Int = cursorCol

    /** Text of one screen row, trailing padding spaces trimmed. Out of range → "". */
    @Synchronized
    fun lineText(row: Int): String {
        if (row < 0 || row >= nrows) return ""
        val line = screen[row]
        var end = line.chars.size
        while (end > 0 && line.chars[end - 1] == ' ') end--
        return String(line.chars, 0, end)
    }

    /**
     * Packed attribute per cell of one screen row (see class KDoc for the layout).
     * A defensive copy is returned; UI must decode, not mutate.
     */
    @Synchronized
    fun lineAttrs(row: Int): IntArray {
        if (row < 0 || row >= nrows) return IntArray(0)
        return screen[row].attrs.copyOf()
    }

    /** Number of lines currently held in the scrollback ring. */
    @Synchronized fun scrollbackCount(): Int = scrollback.size

    /**
     * Scrollback line by index; 0 = oldest line, [scrollbackCount] - 1 = newest.
     * Out of range → "".
     */
    @Synchronized
    fun scrollbackLine(index: Int): String {
        if (index < 0 || index >= scrollback.size) return ""
        val line = scrollback[index]
        var end = line.chars.size
        while (end > 0 && line.chars[end - 1] == ' ') end--
        return String(line.chars, 0, end)
    }

    /** Entire visible screen joined with '\n' — used for process output capture. */
    @Synchronized
    fun dumpPlain(): String = (0 until nrows).joinToString("\n") { lineText(it) }

    // ------------------------------------------------------------------ parser

    private fun handleCodepoint(cp: Int) {
        when (state) {
            ParseState.GROUND -> groundChar(cp)
            ParseState.ESC -> escChar(cp)
            ParseState.CSI -> csiChar(cp)
            ParseState.OSC -> oscChar(cp)
            ParseState.CHARSET -> state = ParseState.GROUND // swallow designator char
        }
    }

    private fun groundChar(cp: Int) {
        when (cp) {
            0x1B -> state = ParseState.ESC
            0x0D -> cursorCol = 0
            0x0A -> { cursorCol = 0; lineFeed() } // LF as newline (see class KDoc)
            0x0B, 0x0C -> lineFeed()
            0x08 -> if (cursorCol > 0) cursorCol--
            0x09 -> cursorCol = minOf(((cursorCol / 8) + 1) * 8, ncols - 1)
            0x07 -> Unit // BEL — audible only; ignored
            else -> if (cp in 0x20..0x7E || cp >= 0xA0) printCodepoint(cp)
        }
    }

    private fun printCodepoint(cp: Int) {
        if (cp < 0x10000) {
            putChar(cp.toChar())
        } else {
            val v = cp - 0x10000
            putChar(((v shr 10) + 0xD800).toChar())
            putChar(((v and 0x3FF) + 0xDC00).toChar())
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= ncols) {
            // Word wrap at the column limit: continue on the next line.
            cursorCol = 0
            lineFeed()
        }
        val line = screen[cursorRow]
        line.chars[cursorCol] = ch
        line.attrs[cursorCol] = currentAttr()
        cursorCol++
    }

    private fun escChar(cp: Int) {
        state = ParseState.GROUND
        when (cp) {
            '['.code -> {
                state = ParseState.CSI
                csiParams.clear()
                csiCurrent = 0
                csiHasCurrent = false
                csiPrivate = false
            }
            ']'.code -> {
                state = ParseState.OSC
                oscEscSeen = false
            }
            '('.code, ')'.code -> state = ParseState.CHARSET
            'M'.code -> reverseIndex()          // ESC M — reverse index (line move up)
            'E'.code -> { cursorCol = 0; lineFeed() } // ESC E — NEL
            'D'.code -> lineFeed()              // ESC D — index
            '7'.code -> { savedRow = cursorRow; savedCol = cursorCol }
            '8'.code -> { cursorRow = savedRow.coerceIn(0, nrows - 1); cursorCol = savedCol.coerceIn(0, ncols - 1) }
            'c'.code -> fullReset()
            else -> Unit                        // =, >, #, and anything else: ignore
        }
    }

    private fun oscChar(cp: Int) {
        when {
            cp == 0x07 -> state = ParseState.GROUND                    // BEL terminator
            oscEscSeen && cp == '\\'.code -> state = ParseState.GROUND // ESC \ (ST)
            else -> oscEscSeen = cp == 0x1B
        }
    }

    private fun csiChar(cp: Int) {
        when (cp) {
            in '0'.code..'9'.code -> {
                csiHasCurrent = true
                csiCurrent = (csiCurrent * 10) + (cp - '0'.code)
            }
            ';'.code, ':'.code -> {
                if (csiParams.size < 32) csiParams.add(if (csiHasCurrent) csiCurrent else -1)
                csiCurrent = 0
                csiHasCurrent = false
            }
            '?'.code -> csiPrivate = true
            in 0x40..0x7E -> {
                if (csiParams.size < 32) csiParams.add(if (csiHasCurrent) csiCurrent else -1)
                dispatchCsi(cp)
                state = ParseState.GROUND
            }
            else -> Unit // intermediate bytes (0x20–0x2F) ignored
        }
    }

    private fun dispatchCsi(final: Int) {
        fun p(index: Int, default: Int): Int =
            if (index < csiParams.size && csiParams[index] >= 0) csiParams[index] else default

        when (final) {
            'A'.code -> cursorRow = maxOf(0, cursorRow - maxOf(1, p(0, 1)))
            'B'.code -> cursorRow = minOf(nrows - 1, cursorRow + maxOf(1, p(0, 1)))
            'C'.code -> cursorCol = minOf(ncols - 1, cursorCol + maxOf(1, p(0, 1)))
            'D'.code -> cursorCol = maxOf(0, cursorCol - maxOf(1, p(0, 1)))
            'H'.code, 'f'.code -> { // CUP / HVP — 1-based position
                cursorRow = (p(0, 1) - 1).coerceIn(0, nrows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, ncols - 1)
            }
            'J'.code -> clearDisplay(p(0, 0))
            'K'.code -> eraseLine(p(0, 0))
            'm'.code -> if (!csiPrivate) applySgr()
            'G'.code -> cursorCol = (p(0, 1) - 1).coerceIn(0, ncols - 1) // CHA
            'd'.code -> cursorRow = (p(0, 1) - 1).coerceIn(0, nrows - 1) // VPA
            's'.code -> { savedRow = cursorRow; savedCol = cursorCol }
            'u'.code -> { cursorRow = savedRow.coerceIn(0, nrows - 1); cursorCol = savedCol.coerceIn(0, ncols - 1) }
            'L'.code -> if (!csiPrivate) insertLines(maxOf(1, p(0, 1)))
            'M'.code -> if (!csiPrivate) deleteLines(maxOf(1, p(0, 1)))
            'X'.code -> eraseChars(maxOf(1, p(0, 1)))
            else -> Unit // h/l/r/S/T/c and friends: ignored
        }
    }

    // ------------------------------------------------------------------ screen ops

    private fun currentAttr(): Int {
        var a = (fg and 0x1FF) or ((bg and 0x1FF) shl 9)
        if (bold) a = a or (1 shl 18)
        if (italic) a = a or (1 shl 19)
        if (reverse) a = a or (1 shl 20)
        return a
    }

    private fun resetAttrs() {
        fg = 0; bg = 0; bold = false; italic = false; reverse = false
    }

    private fun lineFeed() {
        if (cursorRow + 1 >= nrows) scrollUp() else cursorRow++
    }

    /** Pushes the top screen line into the scrollback ring and blanks the bottom line. */
    private fun scrollUp() {
        scrollback.addLast(screen.removeAt(0))
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        screen.add(blankLine(ncols))
        cursorRow = nrows - 1
    }

    /** ESC M at the top line: blank line inserted at top, bottom line discarded. */
    private fun reverseIndex() {
        if (cursorRow == 0) {
            screen.removeAt(nrows - 1)
            screen.add(0, blankLine(ncols))
        } else {
            cursorRow--
        }
    }

    private fun clearDisplay(mode: Int) {
        when (mode) {
            0 -> {
                val line = screen[cursorRow]
                for (c in cursorCol until ncols) { line.chars[c] = ' '; line.attrs[c] = 0 }
                for (r in (cursorRow + 1) until nrows) blank(screen[r])
            }
            1 -> {
                for (r in 0 until cursorRow) blank(screen[r])
                val line = screen[cursorRow]
                for (c in 0..minOf(cursorCol, ncols - 1)) { line.chars[c] = ' '; line.attrs[c] = 0 }
            }
            else -> { // 2 = whole screen, 3 = also clear scrollback
                for (r in 0 until nrows) blank(screen[r])
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until ncols) { line.chars[c] = ' '; line.attrs[c] = 0 }
            1 -> for (c in 0..minOf(cursorCol, ncols - 1)) { line.chars[c] = ' '; line.attrs[c] = 0 }
            else -> blank(line)
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen[cursorRow]
        val end = minOf(ncols, cursorCol + count)
        for (c in cursorCol until end) { line.chars[c] = ' '; line.attrs[c] = 0 }
    }

    private fun insertLines(count: Int) {
        val n = count.coerceIn(1, nrows - cursorRow)
        repeat(n) {
            screen.removeAt(nrows - 1)
            screen.add(cursorRow, blankLine(ncols))
        }
    }

    private fun deleteLines(count: Int) {
        val n = count.coerceIn(1, nrows - cursorRow)
        repeat(n) {
            screen.removeAt(cursorRow)
            screen.add(blankLine(ncols))
        }
    }

    private fun fullReset() {
        resetAttrs()
        for (r in 0 until nrows) blank(screen[r])
        cursorRow = 0
        cursorCol = 0
        state = ParseState.GROUND
        carry = ByteArray(0)
    }

    private fun blank(line: Line) {
        line.chars.fill(' ')
        line.attrs.fill(0)
    }

    private fun blankLine(cols: Int): Line = Line(CharArray(cols) { ' ' }, IntArray(cols))

    private fun resizeLine(line: Line, cols: Int): Line {
        if (line.chars.size == cols) return line
        val chars = CharArray(cols) { ' ' }
        val attrs = IntArray(cols)
        val n = minOf(cols, line.chars.size)
        System.arraycopy(line.chars, 0, chars, 0, n)
        System.arraycopy(line.attrs, 0, attrs, 0, n)
        return Line(chars, attrs)
    }

    // ------------------------------------------------------------------ SGR

    private fun applySgr() {
        if (csiParams.isEmpty() || csiParams[0] == -1) {
            resetAttrs()
            return
        }
        var i = 0
        while (i < csiParams.size) {
            val v = csiParams[i]
            when (v) {
                -1, 0 -> resetAttrs()
                1 -> bold = true
                3 -> italic = true
                7 -> reverse = true
                22 -> bold = false
                23 -> italic = false
                27 -> reverse = false
                39 -> fg = 0
                49 -> bg = 0
                in 30..37 -> fg = v - 29          // 30..37 → palette 1..8
                in 40..47 -> bg = v - 39          // 40..47 → palette 1..8
                in 90..97 -> fg = v - 89 + 8      // 90..97 → palette 9..16
                in 100..107 -> bg = v - 99 + 8    // 100..107 → palette 9..16
                38, 48 -> {
                    val isFg = v == 38
                    if (i + 2 < csiParams.size && csiParams[i + 1] == 5) {
                        // 38;5;n — 256-color palette; stored as n + 17 (see class KDoc).
                        val idx = csiParams[i + 2].coerceIn(0, 255)
                        if (isFg) fg = idx + 17 else bg = idx + 17
                        i += 2
                    } else if (i + 4 < csiParams.size && csiParams[i + 1] == 2) {
                        // 38;2;r;g;b truecolor — no cell bits for RGB: consume and keep current color.
                        i += 4
                    }
                }
                else -> Unit // underline/dim/strikethrough and unknown codes: ignored
            }
            i++
        }
    }

    // ------------------------------------------------------------------ UTF-8

    private fun utf8SequenceLen(first: Int): Int = when {
        first < 0x80 -> 1
        first < 0xC0 -> -1      // continuation byte without a start byte
        first < 0xE0 -> 2
        first < 0xF0 -> 3
        first < 0xF8 -> 4
        else -> -1
    }

    private fun decodeUtf8(bytes: ByteArray, off: Int, len: Int): Int = when (len) {
        1 -> bytes[off].toInt() and 0x7F
        2 -> ((bytes[off].toInt() and 0x1F) shl 6) or (bytes[off + 1].toInt() and 0x3F)
        3 -> ((bytes[off].toInt() and 0x0F) shl 12) or
            ((bytes[off + 1].toInt() and 0x3F) shl 6) or
            (bytes[off + 2].toInt() and 0x3F)
        else -> ((bytes[off].toInt() and 0x07) shl 18) or
            ((bytes[off + 1].toInt() and 0x3F) shl 12) or
            ((bytes[off + 2].toInt() and 0x3F) shl 6) or
            (bytes[off + 3].toInt() and 0x3F)
    }

    private companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
