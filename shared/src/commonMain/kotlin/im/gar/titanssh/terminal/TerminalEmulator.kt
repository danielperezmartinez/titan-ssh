package im.gar.titanssh.terminal

/**
 * A pragmatic VT100/ANSI terminal emulator: a fixed [columns]x[rows] cell grid
 * with a cursor, a bounded scrollback, and a parser for the escape sequences a
 * remote shell emits in day-to-day use. It is fed the raw bytes of an
 * [im.gar.titanssh.ssh.SshShell] and produces immutable [TerminalSnapshot]s for
 * the UI to render.
 *
 * ## Scope (v1)
 * Implemented: printable UTF-8 text with line wrap, the C0 controls
 * `BEL/BS/HT/LF/VT/FF/CR`, CSI cursor motion (`CUU/CUD/CUF/CUB/CUP/HVP`, `CHA`),
 * erase (`ED/EL`), `SGR` rendition (bold, inverse, the 16 ANSI colours, `38/48;5`
 * indexed and `38/48;2` true colour), save/restore cursor and reverse index,
 * and it consumes-and-ignores OSC (window title) and private DEC mode sequences
 * so they never leak to the screen as garbage.
 *
 * Deliberately out of scope for v1 (the shell degrades gracefully without them):
 * the alternate screen buffer, scroll regions (`DECSTBM`), tab-stop programming,
 * and character-set selection. These are noted in the terminal task.
 *
 * Not thread-safe: callers confine [feed]/[resize]/[snapshot] to a single
 * coroutine (the session tab does this behind a mutex).
 */
class TerminalEmulator(
    columns: Int = 80,
    rows: Int = 24,
    private val maxScrollback: Int = 2000,
) {
    var columns = columns.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    private var screen: Array<Array<TerminalCell>> = blankScreen(this.columns, this.rows)
    private val scrollback = ArrayDeque<TerminalLine>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0

    // Current rendition (SGR state).
    private var fg: TermColor = TermColor.Default
    private var bg: TermColor = TermColor.Default
    private var bold = false
    private var inverse = false

    // Parser state.
    private var state = State.GROUND
    private val params = StringBuilder()
    private var csiPrivate = false

    // Carry for a multi-byte UTF-8 sequence split across [feed] calls.
    private var utf8Carry = ByteArray(0)

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, ESC_INTERMEDIATE }

    /** Feeds a chunk of raw output bytes, updating the grid. */
    fun feed(bytes: ByteArray) {
        val input = if (utf8Carry.isEmpty()) bytes else utf8Carry + bytes
        utf8Carry = ByteArray(0)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            when (state) {
                State.GROUND -> {
                    when {
                        b < 0x80 -> {
                            handleGroundByte(b)
                            i++
                        }
                        else -> {
                            // Multi-byte UTF-8 lead: decode a full code point or carry it.
                            val len = utf8Length(b)
                            if (i + len > input.size) {
                                utf8Carry = input.copyOfRange(i, input.size)
                                return
                            }
                            val cp = decodeUtf8(input, i, len)
                            putChar(if (cp in 0..0xFFFF) cp.toChar() else '?')
                            i += len
                        }
                    }
                }
                State.ESC -> {
                    handleEscByte(b)
                    i++
                }
                State.ESC_INTERMEDIATE -> {
                    // Consume the single byte following an intermediate escape (e.g. ESC ( B).
                    state = State.GROUND
                    i++
                }
                State.CSI -> {
                    handleCsiByte(b)
                    i++
                }
                State.OSC -> {
                    // OSC string, terminated by BEL or ST (ESC \).
                    when (b) {
                        0x07 -> state = State.GROUND
                        0x1B -> state = State.OSC_ESC
                    }
                    i++
                }
                State.OSC_ESC -> {
                    state = State.GROUND
                    i++
                }
            }
        }
    }

    private fun handleGroundByte(b: Int) {
        when (b) {
            0x07 -> Unit // BEL
            0x08 -> if (cursorCol > 0) cursorCol-- // BS
            0x09 -> cursorCol = minOf(columns - 1, ((cursorCol / TAB) + 1) * TAB) // HT
            0x0A, 0x0B, 0x0C -> lineFeed() // LF/VT/FF
            0x0D -> cursorCol = 0 // CR
            0x1B -> beginEscape()
            else -> if (b >= 0x20) putChar(b.toChar())
        }
    }

    private fun handleEscByte(b: Int) {
        when (b.toChar()) {
            '[' -> {
                state = State.CSI
                params.clear()
                csiPrivate = false
            }
            ']' -> state = State.OSC
            '7' -> { savedRow = cursorRow; savedCol = cursorCol; state = State.GROUND } // DECSC
            '8' -> { cursorRow = savedRow; cursorCol = savedCol; state = State.GROUND } // DECRC
            'M' -> { reverseIndex(); state = State.GROUND } // RI
            'D' -> { lineFeed(); state = State.GROUND } // IND
            'E' -> { cursorCol = 0; lineFeed(); state = State.GROUND } // NEL
            'c' -> { reset(); state = State.GROUND } // RIS
            // Intermediate bytes that take one more byte (charset selection etc.).
            '(', ')', '*', '+', '#', '%' -> state = State.ESC_INTERMEDIATE
            else -> state = State.GROUND
        }
    }

    private fun handleCsiByte(b: Int) {
        val c = b.toChar()
        when {
            c == '?' || c == '>' || c == '!' -> csiPrivate = true // private markers
            c in '0'..'9' || c == ';' || c == ':' -> params.append(c)
            b in 0x20..0x2F -> Unit // intermediate bytes, ignored
            b in 0x40..0x7E -> {
                dispatchCsi(c)
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun dispatchCsi(final: Char) {
        val args = parseParams()
        if (csiPrivate) {
            // Private DEC modes (cursor visibility, alt screen, bracketed paste…):
            // consumed and ignored in v1 so they never print as garbage.
            return
        }
        when (final) {
            'A' -> cursorRow = (cursorRow - argOr(args, 0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + argOr(args, 0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + argOr(args, 0, 1)).coerceAtMost(columns - 1)
            'D' -> cursorCol = (cursorCol - argOr(args, 0, 1)).coerceAtLeast(0)
            'G' -> cursorCol = (argOr(args, 0, 1) - 1).coerceIn(0, columns - 1) // CHA
            'd' -> cursorRow = (argOr(args, 0, 1) - 1).coerceIn(0, rows - 1) // VPA
            'H', 'f' -> { // CUP / HVP
                cursorRow = (argOr(args, 0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (argOr(args, 1, 1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(argOr(args, 0, 0))
            'K' -> eraseLine(argOr(args, 0, 0))
            'm' -> applySgr(args)
            else -> Unit // unsupported CSI: ignore
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= columns) {
            cursorCol = 0
            lineFeed()
        }
        screen[cursorRow][cursorCol] = TerminalCell(ch, fg, bg, bold, inverse)
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow >= rows - 1) {
            val evicted = screen[0].toList()
            scrollback.addLast(evicted)
            while (scrollback.size > maxScrollback) scrollback.removeFirst()
            for (r in 0 until rows - 1) screen[r] = screen[r + 1]
            screen[rows - 1] = blankRow(columns)
        } else {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == 0) {
            for (r in rows - 1 downTo 1) screen[r] = screen[r - 1]
            screen[0] = blankRow(columns)
        } else {
            cursorRow--
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor to end of screen
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screen[r] = blankRow(columns)
            }
            1 -> { // start of screen to cursor
                for (r in 0 until cursorRow) screen[r] = blankRow(columns)
                eraseLine(1)
            }
            2, 3 -> { // whole screen (3 also clears scrollback)
                for (r in 0 until rows) screen[r] = blankRow(columns)
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = screen[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until columns) row[c] = TerminalCell.Blank
            1 -> for (c in 0..cursorCol.coerceAtMost(columns - 1)) row[c] = TerminalCell.Blank
            2 -> for (c in 0 until columns) row[c] = TerminalCell.Blank
        }
    }

    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) return resetRendition()
        var i = 0
        while (i < args.size) {
            when (val n = args[i]) {
                0 -> resetRendition()
                1 -> bold = true
                22 -> bold = false
                7 -> inverse = true
                27 -> inverse = false
                in 30..37 -> fg = TermColor.Indexed(n - 30)
                39 -> fg = TermColor.Default
                in 40..47 -> bg = TermColor.Indexed(n - 40)
                49 -> bg = TermColor.Default
                in 90..97 -> fg = TermColor.Indexed(n - 90 + 8)
                in 100..107 -> bg = TermColor.Indexed(n - 100 + 8)
                38 -> i = readExtendedColor(args, i) { fg = it }
                48 -> i = readExtendedColor(args, i) { bg = it }
                else -> Unit
            }
            i++
        }
    }

    /** Reads a `38/48;5;n` or `38/48;2;r;g;b` colour starting at [start]; returns the last index consumed. */
    private inline fun readExtendedColor(args: List<Int>, start: Int, set: (TermColor) -> Unit): Int {
        return when (args.getOrNull(start + 1)) {
            5 -> {
                args.getOrNull(start + 2)?.let { set(TermColor.Indexed(it.coerceIn(0, 255))) }
                start + 2
            }
            2 -> {
                val r = args.getOrNull(start + 2) ?: 0
                val g = args.getOrNull(start + 3) ?: 0
                val b = args.getOrNull(start + 4) ?: 0
                set(TermColor.Rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
                start + 4
            }
            else -> start
        }
    }

    private fun resetRendition() {
        fg = TermColor.Default
        bg = TermColor.Default
        bold = false
        inverse = false
    }

    private fun beginEscape() {
        state = State.ESC
    }

    /** Resizes the grid, preserving content top-left and clamping the cursor. */
    fun resize(columns: Int, rows: Int) {
        val newCols = columns.coerceAtLeast(1)
        val newRows = rows.coerceAtLeast(1)
        if (newCols == this.columns && newRows == this.rows) return
        val next = blankScreen(newCols, newRows)
        val copyRows = minOf(this.rows, newRows)
        val copyCols = minOf(this.columns, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) next[r][c] = screen[r][c]
        }
        screen = next
        this.columns = newCols
        this.rows = newRows
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
    }

    /** Clears the screen, scrollback and rendition (full reset, RIS). */
    fun reset() {
        screen = blankScreen(columns, rows)
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        resetRendition()
        state = State.GROUND
        utf8Carry = ByteArray(0)
    }

    /** Produces an immutable snapshot of the current state. */
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        scrollback = scrollback.toList(),
        screen = screen.map { it.toList() },
        columns = columns,
        rows = rows,
        cursorRow = cursorRow,
        cursorColumn = cursorCol,
    )

    private companion object {
        const val TAB = 8

        fun blankRow(cols: Int): Array<TerminalCell> = Array(cols) { TerminalCell.Blank }

        fun blankScreen(cols: Int, rows: Int): Array<Array<TerminalCell>> =
            Array(rows) { blankRow(cols) }

        fun utf8Length(lead: Int): Int = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }

        fun decodeUtf8(bytes: ByteArray, offset: Int, len: Int): Int {
            var cp = when (len) {
                2 -> bytes[offset].toInt() and 0x1F
                3 -> bytes[offset].toInt() and 0x0F
                4 -> bytes[offset].toInt() and 0x07
                else -> return 0xFFFD
            }
            for (k in 1 until len) {
                val cont = bytes[offset + k].toInt() and 0xFF
                if (cont and 0xC0 != 0x80) return 0xFFFD
                cp = (cp shl 6) or (cont and 0x3F)
            }
            return cp
        }

        fun parseParamsFrom(raw: String): List<Int> =
            if (raw.isEmpty()) emptyList()
            else raw.split(';').map { part ->
                // A `:`-subparam (used by some SGR true-colour forms) collapses to its head.
                part.substringBefore(':').toIntOrNull() ?: 0
            }

        fun argOr(args: List<Int>, index: Int, default: Int): Int =
            args.getOrNull(index)?.takeIf { it != 0 } ?: default
    }

    private fun parseParams(): List<Int> = parseParamsFrom(params.toString())
}
