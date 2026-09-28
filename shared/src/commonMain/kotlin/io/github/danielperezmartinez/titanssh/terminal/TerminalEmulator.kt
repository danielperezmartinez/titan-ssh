package io.github.danielperezmartinez.titanssh.terminal

/**
 * A pragmatic VT100/xterm terminal emulator: a fixed [columns]x[rows] cell grid
 * with a cursor, a bounded scrollback, and a parser for the escape sequences a
 * remote shell emits in day-to-day use. It is fed the raw bytes of an
 * [io.github.danielperezmartinez.titanssh.ssh.SshShell] and produces immutable [TerminalSnapshot]s for
 * the UI to render.
 *
 * ## Scope
 * Implemented: UTF-8 text (wide CJK/emoji glyphs take two cells) with deferred
 * autowrap (`DECAWM`), the C0 controls `BEL/BS/HT/LF/VT/FF/CR`, CSI cursor
 * motion (`CUU/CUD/CUF/CUB/CNL/CPL/CUP/HVP/CHA/VPA`), erase (`ED/EL/ECH`),
 * line/char editing (`IL/DL/ICH/DCH/REP`), the alternate screen buffer
 * (`47/1047/1049`) with no scrollback of its own, scroll regions (`DECSTBM`)
 * with region-aware `LF/RI/SU/SD`, `SGR` rendition (bold, dim, italic,
 * underline, inverse, invisible, strikethrough, the 16 ANSI colours, `38/48;5`
 * indexed and `38/48;2` true colour), save/restore cursor (`DECSC/DECRC`,
 * `CSI s/u`), reverse index, cursor visibility (`DECTCEM`), application cursor
 * keys (`DECCKM`), bracketed paste, and the status reports a shell may wait
 * for (`DSR`, primary `DA`), answered through [takeResponses].
 *
 * Lines that wrap at the right margin are marked ([TerminalRow.wrapped]), so
 * [resize] re-flows the main screen and its scrollback to the new width
 * instead of cutting them, and keeps the cursor's line on screen.
 *
 * Still out of scope (the shell degrades gracefully without them): origin mode
 * (`DECOM`), insert mode, tab-stop programming, combining characters and
 * character-set selection.
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

    /** One mutable screen row; [freeze] hands out a cached immutable copy until it changes. */
    private class Line(var cells: Array<TerminalCell>, var wrapped: Boolean = false, private var frozen: TerminalRow? = null) {

        fun touch() {
            frozen = null
        }

        fun freeze(): TerminalRow = frozen ?: TerminalRow(cells.toList(), wrapped).also { frozen = it }
    }

    private var screen: Array<Line> = blankScreen(this.columns, this.rows)
    private val scrollback = ArrayDeque<TerminalRow>()
    private var scrollbackView: List<TerminalRow>? = null

    private var cursorRow = 0
    private var cursorCol = 0

    /** The cursor sits on the last column after writing it: the next glyph wraps first. */
    private var wrapPending = false

    private var savedRow = 0
    private var savedCol = 0
    private var savedStyle = CellStyle.Default

    // Scroll region (DECSTBM), 0-based inclusive; the whole screen by default.
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    // Alternate screen buffer (xterm 47/1047/1049): a full-screen app (tmux, vim,
    // less, htop) switches to it so its UI never lands in the scrollback. The alt
    // screen keeps no scrollback of its own; leaving it restores the main screen.
    private var inAltScreen = false
    private var savedMainScreen: Array<Line>? = null
    private var altReturnRow = 0
    private var altReturnCol = 0

    // Modes.
    private var autoWrap = true
    private var cursorVisible = true
    private var applicationCursorKeys = false
    private var bracketedPaste = false

    // Current rendition (SGR state), shared by every cell written with it.
    private var style = CellStyle.Default
    private var lastPrinted: Int = -1

    // Parser state.
    private var state = State.GROUND
    private val params = StringBuilder()
    private var csiPrivate = false

    // Carry for a multi-byte UTF-8 sequence split across [feed] calls.
    private var utf8Carry = ByteArray(0)

    // Replies to status queries, for the caller to send back to the remote.
    private val responses = StringBuilder()

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
                            putCodePoint(decodeUtf8(input, i, len))
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

    /** Replies owed to the remote (cursor position and device reports) since the last call. */
    fun takeResponses(): ByteArray? {
        if (responses.isEmpty()) return null
        val out = responses.toString().encodeToByteArray()
        responses.clear()
        return out
    }

    private fun handleGroundByte(b: Int) {
        when (b) {
            0x07 -> Unit // BEL
            0x08 -> { // BS
                wrapPending = false
                if (cursorCol > 0) cursorCol--
            }
            0x09 -> { // HT
                wrapPending = false
                cursorCol = minOf(columns - 1, ((cursorCol / TAB) + 1) * TAB)
            }
            0x0A, 0x0B, 0x0C -> lineFeed() // LF/VT/FF
            0x0D -> { // CR
                wrapPending = false
                cursorCol = 0
            }
            0x1B -> state = State.ESC
            else -> if (b >= 0x20 && b != 0x7F) putCodePoint(b)
        }
    }

    private fun handleEscByte(b: Int) {
        state = State.GROUND
        when (b.toChar()) {
            '[' -> {
                state = State.CSI
                params.clear()
                csiPrivate = false
            }
            ']' -> state = State.OSC
            // DCS/SOS/PM/APC strings: swallowed like an OSC.
            'P', 'X', '^', '_' -> state = State.OSC
            '7' -> saveCursor() // DECSC
            '8' -> restoreCursor() // DECRC
            'M' -> reverseIndex() // RI
            'D' -> lineFeed() // IND
            'E' -> { // NEL
                cursorCol = 0
                lineFeed()
            }
            'c' -> reset() // RIS
            // Intermediate bytes that take one more byte (charset selection etc.).
            '(', ')', '*', '+', '#', '%' -> state = State.ESC_INTERMEDIATE
            else -> Unit
        }
    }

    private fun handleCsiByte(b: Int) {
        val c = b.toChar()
        when {
            c == '?' || c == '>' || c == '!' || c == '=' -> csiPrivate = true // private markers
            c in '0'..'9' || c == ';' || c == ':' -> params.append(c)
            b in 0x20..0x2F -> Unit // intermediate bytes, ignored
            b in 0x40..0x7E -> {
                state = State.GROUND
                dispatchCsi(c)
            }
            else -> state = State.GROUND
        }
    }

    private fun dispatchCsi(final: Char) {
        val args = parseParams()
        if (csiPrivate) {
            when (final) {
                'h' -> setPrivateModes(args, true)
                'l' -> setPrivateModes(args, false)
                else -> Unit
            }
            return
        }
        // Every motion leaves the deferred-wrap state; printing is the only way to keep it.
        if (final != 'm' && final != 'n' && final != 'c') wrapPending = false
        when (final) {
            'A' -> cursorRow = (cursorRow - argOr(args, 0, 1)).coerceAtLeast(if (cursorRow >= scrollTop) scrollTop else 0)
            'B' -> cursorRow = (cursorRow + argOr(args, 0, 1)).coerceAtMost(if (cursorRow <= scrollBottom) scrollBottom else rows - 1)
            'C' -> cursorCol = (cursorCol + argOr(args, 0, 1)).coerceAtMost(columns - 1)
            'D' -> cursorCol = (cursorCol - argOr(args, 0, 1)).coerceAtLeast(0)
            'E' -> { // CNL
                cursorRow = (cursorRow + argOr(args, 0, 1)).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> { // CPL
                cursorRow = (cursorRow - argOr(args, 0, 1)).coerceAtLeast(0)
                cursorCol = 0
            }
            'G', '`' -> cursorCol = (argOr(args, 0, 1) - 1).coerceIn(0, columns - 1) // CHA / HPA
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
            'b' -> if (lastPrinted >= 0) repeat(argOr(args, 0, 1).coerceAtMost(columns * rows)) { putCodePoint(lastPrinted) } // REP
            'r' -> setScrollRegion(args) // DECSTBM
            's' -> saveCursor() // SCOSC
            'u' -> restoreCursor() // SCORC
            'm' -> applySgr(args)
            'n' -> when (args.firstOrNull()) { // DSR
                5 -> responses.append("\u001B[0n")
                6 -> responses.append("\u001B[${cursorRow + 1};${cursorCol + 1}R")
                else -> Unit
            }
            'c' -> if ((args.firstOrNull() ?: 0) == 0) responses.append("\u001B[?1;2c") // DA: a VT100 with AVO
            else -> Unit // unsupported CSI: ignore
        }
    }

    /** Applies the private DEC modes we honor. */
    private fun setPrivateModes(args: List<Int>, set: Boolean) {
        for (mode in args) {
            when (mode) {
                1 -> applicationCursorKeys = set
                7 -> {
                    autoWrap = set
                    if (!set) wrapPending = false
                }
                25 -> cursorVisible = set
                2004 -> bracketedPaste = set
                // 1049 also saves/restores the cursor around the switch; 47/1047
                // switch buffers without the cursor dance.
                1049 -> if (set) enterAltScreen(saveCursor = true) else exitAltScreen(restoreCursor = true)
                47, 1047 -> if (set) enterAltScreen(saveCursor = false) else exitAltScreen(restoreCursor = false)
                else -> Unit // other private modes: ignored
            }
        }
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedStyle = style
    }

    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, columns - 1)
        style = savedStyle
        wrapPending = false
    }

    private fun enterAltScreen(saveCursor: Boolean) {
        if (inAltScreen) return
        if (saveCursor) {
            altReturnRow = cursorRow
            altReturnCol = cursorCol
        }
        savedMainScreen = screen
        screen = blankScreen(columns, rows)
        inAltScreen = true
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
    }

    private fun exitAltScreen(restoreCursor: Boolean) {
        val main = savedMainScreen ?: return
        screen = main
        savedMainScreen = null
        inAltScreen = false
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
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
            if (scrollTop == 0 && !inAltScreen) pushScrollback(evicted.freeze())
            for (r in scrollTop until scrollBottom) screen[r] = screen[r + 1]
            screen[scrollBottom] = blankLine(columns)
        }
    }

    private fun pushScrollback(row: TerminalRow) {
        scrollback.addLast(row)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        scrollbackView = null
    }

    /** Scrolls the region down by [n] (blank lines enter at the top of the region). */
    private fun scrollRegionDown(n: Int) {
        repeat(n.coerceIn(0, scrollBottom - scrollTop + 1)) {
            for (r in scrollBottom downTo scrollTop + 1) screen[r] = screen[r - 1]
            screen[scrollTop] = blankLine(columns)
        }
    }

    /** IL: inserts [n] blank lines at the cursor, within the scroll region. */
    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(0, scrollBottom - cursorRow + 1)
        for (r in scrollBottom downTo cursorRow + count) screen[r] = screen[r - count]
        for (r in cursorRow until cursorRow + count) screen[r] = blankLine(columns)
        cursorCol = 0
    }

    /** DL: deletes [n] lines at the cursor, within the scroll region. */
    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(0, scrollBottom - cursorRow + 1)
        for (r in cursorRow..scrollBottom - count) screen[r] = screen[r + count]
        for (r in scrollBottom - count + 1..scrollBottom) screen[r] = blankLine(columns)
        cursorCol = 0
    }

    /** ICH: inserts [n] blank cells at the cursor, shifting the rest of the line right. */
    private fun insertChars(n: Int) {
        val line = screen[cursorRow]
        val row = line.cells
        val count = n.coerceIn(0, columns - cursorCol)
        for (c in columns - 1 downTo cursorCol + count) row[c] = row[c - count]
        for (c in cursorCol until cursorCol + count) row[c] = blankCell()
        line.touch()
    }

    /** DCH: deletes [n] cells at the cursor, shifting the rest of the line left. */
    private fun deleteChars(n: Int) {
        val line = screen[cursorRow]
        val row = line.cells
        val count = n.coerceIn(0, columns - cursorCol)
        for (c in cursorCol until columns - count) row[c] = row[c + count]
        for (c in columns - count until columns) row[c] = blankCell()
        line.touch()
    }

    /** ECH: erases [n] cells from the cursor without shifting. */
    private fun eraseChars(n: Int) {
        val line = screen[cursorRow]
        val end = (cursorCol + n).coerceAtMost(columns)
        for (c in cursorCol until end) line.cells[c] = blankCell()
        line.touch()
    }

    private fun putCodePoint(cp: Int) {
        val width = charWidth(cp)
        if (width == 0) return // combining mark / zero-width: not composed, dropped
        lastPrinted = cp
        if (wrapPending) {
            wrapPending = false
            if (autoWrap) {
                screen[cursorRow].let { it.wrapped = true; it.touch() }
                cursorCol = 0
                lineFeed()
            }
        }
        if (width == 2 && cursorCol == columns - 1) {
            if (columns < 2) return
            // No room for both halves: pad this row and wrap before writing.
            setCell(cursorRow, cursorCol, blankCell())
            if (!autoWrap) return
            screen[cursorRow].let { it.wrapped = true; it.touch() }
            cursorCol = 0
            lineFeed()
        }
        setCell(cursorRow, cursorCol, TerminalCell(cp, style, width))
        if (width == 2) setCell(cursorRow, cursorCol + 1, TerminalCell(' '.code, style, 0))
        val next = cursorCol + width
        if (next >= columns) {
            cursorCol = columns - 1
            wrapPending = autoWrap
        } else {
            cursorCol = next
        }
    }

    /** Writes [cell] at [row],[col], clearing the other half of any wide glyph it overwrites. */
    private fun setCell(row: Int, col: Int, cell: TerminalCell) {
        val line = screen[row]
        val cells = line.cells
        when (cells[col].width) {
            2 -> if (col + 1 < columns) cells[col + 1] = blankCell()
            0 -> if (col > 0) cells[col - 1] = blankCell()
        }
        cells[col] = cell
        line.touch()
    }

    private fun lineFeed() {
        wrapPending = false
        when {
            // At the bottom margin: scroll the region up (feeds scrollback only for
            // a full-height main screen, see scrollRegionUp).
            cursorRow == scrollBottom -> scrollRegionUp(1)
            cursorRow < rows - 1 -> cursorRow++
            // Below the region at the physical bottom: stay put.
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        when {
            cursorRow == scrollTop -> scrollRegionDown(1)
            cursorRow > 0 -> cursorRow--
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor to end of screen
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screen[r] = blankLine(columns)
            }
            1 -> { // start of screen to cursor
                for (r in 0 until cursorRow) screen[r] = blankLine(columns)
                eraseLine(1)
            }
            2 -> for (r in 0 until rows) screen[r] = blankLine(columns)
            3 -> { // the scrollback only (xterm)
                scrollback.clear()
                scrollbackView = null
            }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        val row = line.cells
        when (mode) {
            0 -> {
                for (c in cursorCol until columns) row[c] = blankCell()
                line.wrapped = false
            }
            1 -> for (c in 0..cursorCol.coerceAtMost(columns - 1)) row[c] = blankCell()
            2 -> {
                for (c in 0 until columns) row[c] = blankCell()
                line.wrapped = false
            }
        }
        line.touch()
    }

    /** A blank cell carrying the current background, as erase operations leave behind (BCE). */
    private fun blankCell(): TerminalCell =
        if (style.bg == TermColor.Default && !style.inverse) TerminalCell.Blank
        else TerminalCell(' '.code, CellStyle(bg = style.bg, inverse = style.inverse))

    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) {
            style = CellStyle.Default
            return
        }
        var s = style
        var i = 0
        while (i < args.size) {
            when (val n = args[i]) {
                0 -> s = CellStyle.Default
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                8 -> s = s.copy(invisible = true)
                9 -> s = s.copy(strikethrough = true)
                21 -> s = s.copy(underline = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                28 -> s = s.copy(invisible = false)
                29 -> s = s.copy(strikethrough = false)
                in 30..37 -> s = s.copy(fg = TermColor.Indexed(n - 30))
                39 -> s = s.copy(fg = TermColor.Default)
                in 40..47 -> s = s.copy(bg = TermColor.Indexed(n - 40))
                49 -> s = s.copy(bg = TermColor.Default)
                in 90..97 -> s = s.copy(fg = TermColor.Indexed(n - 90 + 8))
                in 100..107 -> s = s.copy(bg = TermColor.Indexed(n - 100 + 8))
                38 -> {
                    var c: TermColor? = null
                    i = readExtendedColor(args, i) { c = it }
                    c?.let { s = s.copy(fg = it) }
                }
                48 -> {
                    var c: TermColor? = null
                    i = readExtendedColor(args, i) { c = it }
                    c?.let { s = s.copy(bg = it) }
                }
                else -> Unit
            }
            i++
        }
        style = if (s == CellStyle.Default) CellStyle.Default else s
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

    /**
     * Resizes the grid. The main screen and its scrollback are re-flowed to the
     * new width (soft-wrapped lines re-join and wrap again), rows beyond the new
     * height go to the scrollback so the cursor's line stays visible, and a
     * taller screen pulls lines back from it. The alternate screen is only
     * clipped or padded: the full-screen app on it redraws on the resize.
     */
    fun resize(columns: Int, rows: Int) {
        val newCols = columns.coerceAtLeast(1)
        val newRows = rows.coerceAtLeast(1)
        if (newCols == this.columns && newRows == this.rows) return
        val oldCols = this.columns
        val oldRows = this.rows
        if (inAltScreen) {
            val main = savedMainScreen!!
            val (reflowed, row, col) = reflowMain(main, altReturnRow, altReturnCol, newCols, newRows)
            savedMainScreen = reflowed
            altReturnRow = row
            altReturnCol = col
            screen = clipGrid(screen, oldCols, oldRows, newCols, newRows)
            cursorRow = cursorRow.coerceIn(0, newRows - 1)
            cursorCol = cursorCol.coerceIn(0, newCols - 1)
            wrapPending = false
        } else {
            val (reflowed, row, col) = reflowMain(screen, cursorRow, if (wrapPending) cursorCol + 1 else cursorCol, newCols, newRows)
            screen = reflowed
            cursorRow = row
            if (col >= newCols) {
                cursorCol = newCols - 1
                wrapPending = autoWrap
            } else {
                cursorCol = col
                wrapPending = false
            }
        }
        this.columns = newCols
        this.rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        savedRow = savedRow.coerceIn(0, newRows - 1)
        savedCol = savedCol.coerceIn(0, newCols - 1)
    }

    /**
     * Re-flows [lines] (a main screen) plus the scrollback to [newCols]x[newRows],
     * rebuilding the scrollback in place. Returns the new screen and the cursor
     * moved along with its text; a column equal to [newCols] means "past the last
     * cell" (a pending wrap).
     */
    private fun reflowMain(
        lines: Array<Line>,
        curRow: Int,
        curCol: Int,
        newCols: Int,
        newRows: Int,
    ): Triple<Array<Line>, Int, Int> {
        val used = maxOf(curRow, lastContentRow(lines)) + 1
        val sources = ArrayList<TerminalRow>(scrollback.size + used)
        sources.addAll(scrollback)
        for (r in 0 until used) sources.add(lines[r].freeze())
        val cursorSource = scrollback.size + curRow

        // Rewrap logical line by logical line, tracking where the cursor lands.
        val out = ArrayList<TerminalRow>(sources.size + 8)
        var cursorOutRow = -1
        var cursorOutCol = 0
        val logical = ArrayList<TerminalCell>()
        var cursorOffset = -1
        for ((index, row) in sources.withIndex()) {
            if (index == cursorSource) cursorOffset = logical.size + curCol
            if (row.wrapped) {
                logical.addAll(row)
                continue
            }
            var end = row.size
            while (end > 0 && row[end - 1].isBlank) end--
            for (c in 0 until end) logical.add(row[c])
            if (cursorOffset >= 0) {
                while (logical.size < cursorOffset) logical.add(TerminalCell.Blank)
            }
            val (rowIndex, colIndex) = rewrap(logical, newCols, out, cursorOffset)
            if (cursorOffset >= 0) {
                cursorOutRow = rowIndex
                cursorOutCol = colIndex
            }
            logical.clear()
            cursorOffset = -1
        }
        if (logical.isNotEmpty()) {
            val (rowIndex, colIndex) = rewrap(logical, newCols, out, cursorOffset)
            if (cursorOffset >= 0) {
                cursorOutRow = rowIndex
                cursorOutCol = colIndex
            }
        }
        if (out.isEmpty()) out.add(TerminalRow(List(newCols) { TerminalCell.Blank }))
        if (cursorOutRow < 0) {
            cursorOutRow = out.size - 1
            cursorOutCol = 0
        }

        // Keep the cursor's line (and what follows it) on screen.
        val lastRow = out.size - 1
        val top = minOf(cursorOutRow, maxOf(0, lastRow - newRows + 1))
        scrollback.clear()
        for (r in 0 until top) scrollback.addLast(out[r])
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        scrollbackView = null
        val next = Array(newRows) { r ->
            val source = out.getOrNull(top + r)
            if (source == null) blankLine(newCols) else Line(source.toTypedArray(), source.wrapped, source)
        }
        return Triple(next, cursorOutRow - top, cursorOutCol)
    }

    /**
     * Appends [cells] (one logical line) to [out] as rows of [cols], never
     * splitting a wide glyph. Returns the row (index into [out]) and column the
     * cell at [cursorOffset] lands on, or the end of the line when it is past it.
     */
    private fun rewrap(cells: List<TerminalCell>, cols: Int, out: MutableList<TerminalRow>, cursorOffset: Int): Pair<Int, Int> {
        var row = ArrayList<TerminalCell>(cols)
        var cursorAt: Pair<Int, Int>? = null
        var i = 0
        while (i < cells.size) {
            val cell = cells[i]
            val w = if (cell.width == 2 && cols >= 2) 2 else 1
            if (row.size + w > cols) {
                while (row.size < cols) row.add(TerminalCell.Blank)
                out.add(TerminalRow(row, wrapped = true))
                row = ArrayList(cols)
            }
            if (cursorAt == null && (i == cursorOffset || (w == 2 && i + 1 == cursorOffset))) cursorAt = out.size to row.size
            if (cell.width == 0) {
                i++
                continue // an orphan spacer (its glyph got cut): drop it
            }
            if (w == 2) {
                row.add(cell)
                row.add(cells.getOrNull(i + 1)?.takeIf { it.width == 0 } ?: TerminalCell(' '.code, cell.style, 0))
                i += if (cells.getOrNull(i + 1)?.width == 0) 2 else 1
            } else {
                row.add(if (cell.width == 2) TerminalCell(cell.codePoint, cell.style, 1) else cell)
                i++
            }
        }
        val endAt = out.size to row.size
        while (row.size < cols) row.add(TerminalCell.Blank)
        out.add(TerminalRow(row, wrapped = false))
        return cursorAt ?: endAt
    }

    /** Last screen row with anything but blanks on it, or -1. */
    private fun lastContentRow(lines: Array<Line>): Int {
        for (r in lines.indices.reversed()) {
            if (lines[r].wrapped || lines[r].cells.any { !it.isBlank }) return r
        }
        return -1
    }

    /**
     * Removes from the main screen and its scrollback every line whose text
     * matches [pattern], shifting what follows up (or pulling scrollback down)
     * so nothing else moves on screen. The line the cursor is on is left alone
     * while it may still be written to. Returns true when a match on that line
     * was skipped, so the caller can try again after more output.
     *
     * Used to hide the automation's own commands (completion sentinels and
     * probes), which only make sense to the app.
     */
    fun eraseLinesMatching(pattern: Regex, scrollbackDepth: Int = 200): Boolean {
        if (inAltScreen) return false
        var pendingOnCursor = false

        // Scrollback tail: whole logical lines that end before the screen starts.
        val firstChecked = maxOf(0, scrollback.size - scrollbackDepth)
        val keep = ArrayList<TerminalRow>()
        var removedAny = false
        var group = ArrayList<TerminalRow>()
        for (index in firstChecked until scrollback.size) {
            group.add(scrollback[index])
            if (!scrollback[index].wrapped) {
                if (pattern.containsMatchIn(textOf(group))) removedAny = true else keep.addAll(group)
                group = ArrayList()
            }
        }
        // A logical line continuing onto the screen is judged with the screen.
        val carried = group

        // Screen: logical lines, never the one holding the cursor.
        val removeScreen = BooleanArray(rows)
        val used = maxOf(cursorRow, lastContentRow(screen))
        var start = 0
        val carriedCount = carried.size
        while (start <= used) {
            var end = start
            while (end < used && screen[end].wrapped) end++
            val rowsOfLine = (start..end).map { screen[it].freeze() }
            val text = textOf(if (start == 0) carried + rowsOfLine else rowsOfLine)
            if (pattern.containsMatchIn(text)) {
                if (cursorRow in start..end) {
                    pendingOnCursor = true
                } else {
                    for (r in start..end) removeScreen[r] = true
                    if (start == 0 && carriedCount > 0) carried.clear()
                }
            }
            start = end + 1
        }
        val removedScreen = removeScreen.count { it }
        if (!removedAny && removedScreen == 0) return pendingOnCursor

        if (removedAny || carried.size != carriedCount) {
            val head = scrollback.take(firstChecked)
            scrollback.clear()
            scrollback.addAll(head)
            scrollback.addAll(keep)
            scrollback.addAll(carried)
            scrollbackView = null
        }
        if (removedScreen > 0) {
            val cursorLine = screen[cursorRow]
            val remaining = screen.filterIndexed { r, _ -> !removeScreen[r] }.toMutableList()
            // Pull scrollback down into the top so the lines below stay put.
            var pulled = 0
            while (pulled < removedScreen && scrollback.isNotEmpty()) {
                val row = scrollback.removeLast()
                remaining.add(0, Line(row.toTypedArray(), row.wrapped, row))
                pulled++
            }
            if (pulled > 0) scrollbackView = null
            while (remaining.size < rows) remaining.add(blankLine(columns))
            screen = remaining.toTypedArray()
            cursorRow = screen.indexOf(cursorLine).coerceIn(0, rows - 1)
        }
        return pendingOnCursor
    }

    private fun textOf(rows: List<TerminalRow>): String = buildString {
        for (row in rows) for (cell in row) if (cell.width != 0) append(cell.text)
    }

    /** Clears the screen, scrollback and rendition (full reset, RIS). */
    fun reset() {
        inAltScreen = false
        savedMainScreen = null
        screen = blankScreen(columns, rows)
        scrollback.clear()
        scrollbackView = null
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        autoWrap = true
        cursorVisible = true
        applicationCursorKeys = false
        bracketedPaste = false
        style = CellStyle.Default
        state = State.GROUND
        utf8Carry = ByteArray(0)
    }

    /**
     * Produces an immutable snapshot of the current state. Cheap to call often:
     * unchanged rows and an unchanged scrollback are shared with the previous
     * snapshot rather than copied.
     */
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        scrollback = scrollbackView ?: scrollback.toList().also { scrollbackView = it },
        screen = screen.map { it.freeze() },
        columns = columns,
        rows = rows,
        cursorRow = cursorRow,
        cursorColumn = cursorCol,
        cursorVisible = cursorVisible,
        altScreen = inAltScreen,
        contentRows = maxOf(cursorRow, lastContentRow(screen)) + 1,
        applicationCursorKeys = applicationCursorKeys,
        bracketedPaste = bracketedPaste,
    )

    private companion object {
        const val TAB = 8

        fun blankLine(cols: Int): Line = Line(Array(cols) { TerminalCell.Blank })

        fun blankScreen(cols: Int, rows: Int): Array<Line> = Array(rows) { blankLine(cols) }

        /** A new grid of [dstCols]x[dstRows] with [src]'s content copied top-left. */
        fun clipGrid(src: Array<Line>, srcCols: Int, srcRows: Int, dstCols: Int, dstRows: Int): Array<Line> {
            val next = blankScreen(dstCols, dstRows)
            val copyRows = minOf(srcRows, dstRows)
            val copyCols = minOf(srcCols, dstCols)
            for (r in 0 until copyRows) {
                for (c in 0 until copyCols) next[r].cells[c] = src[r].cells[c]
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

/**
 * How many cells [cp] takes, as a remote's `wcwidth` would count it: 0 for
 * combining and zero-width marks, 2 for East Asian wide/fullwidth characters and
 * emoji presentation, 1 for the rest.
 */
internal fun charWidth(cp: Int): Int = when {
    cp < 0x300 -> 1
    cp in 0x300..0x36F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF || cp in 0x20D0..0x20FF ||
        cp in 0xFE20..0xFE2F || cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F || cp == 0x2060 || cp == 0xFEFF -> 0
    cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
        cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 -> 2
    cp == 0x231A || cp == 0x231B || cp in 0x23E9..0x23EC || cp == 0x23F0 || cp == 0x23F3 ||
        cp == 0x25FD || cp == 0x25FE || cp == 0x2614 || cp == 0x2615 || cp in 0x2648..0x2653 ||
        cp == 0x267F || cp == 0x2693 || cp == 0x26A1 || cp == 0x26AA || cp == 0x26AB || cp == 0x26BD ||
        cp == 0x26BE || cp == 0x26C4 || cp == 0x26C5 || cp == 0x26CE || cp == 0x26D4 || cp == 0x26EA ||
        cp == 0x26F2 || cp == 0x26F3 || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD || cp == 0x2705 ||
        cp == 0x270A || cp == 0x270B || cp == 0x2728 || cp == 0x274C || cp == 0x274E || cp in 0x2753..0x2755 ||
        cp == 0x2757 || cp in 0x2795..0x2797 || cp == 0x27B0 || cp == 0x27BF || cp == 0x2B1B ||
        cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55 -> 2
    cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF || cp in 0x1F900..0x1F9FF || cp in 0x1FA70..0x1FAFF ||
        cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E || cp in 0x1F191..0x1F19A || cp in 0x1F200..0x1F251 ||
        cp in 0x20000..0x3FFFD -> 2
    else -> 1
}
