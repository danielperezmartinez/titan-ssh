package im.gar.titanssh.terminal

/**
 * A pragmatic VT100/ANSI terminal emulator: a fixed [columns]x[rows] cell grid
 * with a cursor, a bounded scrollback, and a parser for the escape sequences a
 * remote shell emits in day-to-day use. It is fed the raw bytes of an
 * [im.gar.titanssh.ssh.SshShell] and produces immutable [TerminalSnapshot]s for
 * the UI to render.
 *
 * ## Scope
 * Implemented: printable UTF-8 text with line wrap, the C0 controls
 * `BEL/BS/HT/LF/VT/FF/CR`, CSI cursor motion (`CUU/CUD/CUF/CUB/CUP/HVP`, `CHA`),
 * erase (`ED/EL/ECH`), line/char editing (`IL/DL/ICH/DCH`), the alternate screen
 * buffer (`47/1047/1049`) with no scrollback of its own, scroll regions
 * (`DECSTBM`) with region-aware `LF/RI/SU/SD`, `SGR` rendition (bold, inverse,
 * the 16 ANSI colours, `38/48;5` indexed and `38/48;2` true colour),
 * save/restore cursor and reverse index. It consumes-and-ignores OSC (window
 * title) and the private DEC modes it does not act on (cursor visibility,
 * bracketed paste…) so they never leak to the screen as garbage. This is enough
 * for full-screen apps (tmux/screen, vim, less, htop) to render — the base for
 * resilience level 2 ([[Resiliencia nivel 2 auto-tmux o screen]]).
 *
 * Still out of scope (the shell degrades gracefully without them): origin mode
 * (`DECOM`), tab-stop programming, and character-set selection.
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

    // Scroll region (DECSTBM), 0-based inclusive; the whole screen by default.
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    // Alternate screen buffer (xterm 47/1047/1049): a full-screen app (tmux, vim,
    // less, htop) switches to it so its UI never lands in the scrollback. The alt
    // screen keeps no scrollback of its own; leaving it restores the main screen.
    private var inAltScreen = false
    private var savedMainScreen: Array<Array<TerminalCell>>? = null
    private var altReturnRow = 0
    private var altReturnCol = 0

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
            // Private DEC modes. We act on the alternate-screen switch (47/1047/
            // 1049) so full-screen apps render on their own buffer; the rest
            // (cursor visibility, bracketed paste…) are consumed and ignored.
            when (final) {
                'h' -> setPrivateModes(args, true)
                'l' -> setPrivateModes(args, false)
                else -> Unit
            }
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
            'L' -> insertLines(argOr(args, 0, 1)) // IL
            'M' -> deleteLines(argOr(args, 0, 1)) // DL
            '@' -> insertChars(argOr(args, 0, 1)) // ICH
            'P' -> deleteChars(argOr(args, 0, 1)) // DCH
            'X' -> eraseChars(argOr(args, 0, 1)) // ECH
            'S' -> scrollRegionUp(argOr(args, 0, 1)) // SU
            'T' -> scrollRegionDown(argOr(args, 0, 1)) // SD
            'r' -> setScrollRegion(args) // DECSTBM
            'm' -> applySgr(args)
            else -> Unit // unsupported CSI: ignore
        }
    }

    /** Applies the private DEC modes we honor (currently the alternate screen). */
    private fun setPrivateModes(args: List<Int>, set: Boolean) {
        for (mode in args) {
            when (mode) {
                // 1049 also saves/restores the cursor around the switch; 47/1047
                // switch buffers without the cursor dance. We treat them alike for
                // entering/leaving and only 1049 carries the cursor.
                1049 -> if (set) enterAltScreen(saveCursor = true) else exitAltScreen(restoreCursor = true)
                47, 1047 -> if (set) enterAltScreen(saveCursor = false) else exitAltScreen(restoreCursor = false)
                else -> Unit // other private modes: ignored
            }
        }
    }

    private fun enterAltScreen(saveCursor: Boolean) {
        if (inAltScreen) return
        if (saveCursor) { altReturnRow = cursorRow; altReturnCol = cursorCol }
        savedMainScreen = screen
        screen = blankScreen(columns, rows)
        inAltScreen = true
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorCol = 0
    }

    private fun exitAltScreen(restoreCursor: Boolean) {
        val main = savedMainScreen ?: return
        screen = main
        savedMainScreen = null
        inAltScreen = false
        scrollTop = 0
        scrollBottom = rows - 1
        if (restoreCursor) {
            cursorRow = altReturnRow.coerceIn(0, rows - 1)
            cursorCol = altReturnCol.coerceIn(0, columns - 1)
        } else {
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, columns - 1)
        }
    }

    /** DECSTBM: sets the scroll region [top,bottom] (1-based args) and homes the cursor. */
    private fun setScrollRegion(args: List<Int>) {
        val top = (argOr(args, 0, 1) - 1)
        val bottom = (argOr(args, 1, rows) - 1)
        if (top in 0 until bottom && bottom <= rows - 1) {
            scrollTop = top
            scrollBottom = bottom
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        cursorRow = 0
        cursorCol = 0
    }

    /** Scrolls the region up by [n], feeding evicted top lines to scrollback only
     * for a full-height main screen (no region set, not the alt buffer). */
    private fun scrollRegionUp(n: Int) {
        repeat(n.coerceIn(0, scrollBottom - scrollTop + 1)) {
            val evicted = screen[scrollTop]
            if (scrollTop == 0 && !inAltScreen) {
                scrollback.addLast(evicted.toList())
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = blankRow(columns)
        }
    }

    /** Scrolls the region down by [n] (blank lines enter at the top of the region). */
    private fun scrollRegionDown(n: Int) {
        repeat(n.coerceIn(0, scrollBottom - scrollTop + 1)) {
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = blankRow(columns)
        }
    }

    /** IL: inserts [n] blank lines at the cursor, within the scroll region. */
    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(0, scrollBottom - cursorRow + 1)
        for (r in scrollBottom downTo cursorRow + count) screen[r] = screen[r - count]
        for (r in cursorRow until cursorRow + count) screen[r] = blankRow(columns)
    }

    /** DL: deletes [n] lines at the cursor, within the scroll region. */
    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(0, scrollBottom - cursorRow + 1)
        for (r in cursorRow..scrollBottom - count) screen[r] = screen[r + count]
        for (r in scrollBottom - count + 1..scrollBottom) screen[r] = blankRow(columns)
    }

    /** ICH: inserts [n] blank cells at the cursor, shifting the rest of the line right. */
    private fun insertChars(n: Int) {
        val row = screen[cursorRow]
        val count = n.coerceIn(0, columns - cursorCol)
        for (c in columns - 1 downTo cursorCol + count) row[c] = row[c - count]
        for (c in cursorCol until cursorCol + count) row[c] = TerminalCell.Blank
    }

    /** DCH: deletes [n] cells at the cursor, shifting the rest of the line left. */
    private fun deleteChars(n: Int) {
        val row = screen[cursorRow]
        val count = n.coerceIn(0, columns - cursorCol)
        for (c in cursorCol until columns - count) row[c] = row[c + count]
        for (c in columns - count until columns) row[c] = TerminalCell.Blank
    }

    /** ECH: erases [n] cells from the cursor without shifting. */
    private fun eraseChars(n: Int) {
        val row = screen[cursorRow]
        val end = (cursorCol + n).coerceAtMost(columns)
        for (c in cursorCol until end) row[c] = TerminalCell.Blank
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
        when {
            // At the bottom margin: scroll the region up (feeds scrollback only for
            // a full-height main screen, see scrollRegionUp).
            cursorRow == scrollBottom -> scrollRegionUp(1)
            cursorRow < rows - 1 -> cursorRow++
            // Below the region at the physical bottom: stay put.
        }
    }

    private fun reverseIndex() {
        when {
            cursorRow == scrollTop -> scrollRegionDown(1)
            cursorRow > 0 -> cursorRow--
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

    /** Resizes the grid, preserving content top-left and clamping the cursor. The
     * scroll region is reset to the full screen and, if a full-screen app is on
     * the alternate buffer, the saved main screen is resized alongside it. */
    fun resize(columns: Int, rows: Int) {
        val newCols = columns.coerceAtLeast(1)
        val newRows = rows.coerceAtLeast(1)
        if (newCols == this.columns && newRows == this.rows) return
        val oldCols = this.columns
        val oldRows = this.rows
        screen = resizeGrid(screen, oldCols, oldRows, newCols, newRows)
        savedMainScreen = savedMainScreen?.let { resizeGrid(it, oldCols, oldRows, newCols, newRows) }
        this.columns = newCols
        this.rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
    }

    /** Clears the screen, scrollback and rendition (full reset, RIS). */
    fun reset() {
        inAltScreen = false
        savedMainScreen = null
        screen = blankScreen(columns, rows)
        scrollback.clear()
        scrollTop = 0
        scrollBottom = rows - 1
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

        /** A new grid of [dstCols]x[dstRows] with [src]'s content copied top-left. */
        fun resizeGrid(
            src: Array<Array<TerminalCell>>,
            srcCols: Int,
            srcRows: Int,
            dstCols: Int,
            dstRows: Int,
        ): Array<Array<TerminalCell>> {
            val next = blankScreen(dstCols, dstRows)
            val copyRows = minOf(srcRows, dstRows)
            val copyCols = minOf(srcCols, dstCols)
            for (r in 0 until copyRows) {
                for (c in 0 until copyCols) next[r][c] = src[r][c]
            }
            return next
        }

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
