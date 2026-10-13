package io.github.danielperezmartinez.titanssh.terminal

/**
 * A cell of the terminal's document (scrollback + screen). [line] counts from
 * the first line the emulator ever kept, so it stays on the same text while
 * output scrolls and the scrollback cap drops old lines (see
 * [TerminalSnapshot.droppedLines]).
 */
data class DocPos(val line: Long, val column: Int) : Comparable<DocPos> {
    override fun compareTo(other: DocPos): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

/**
 * Reads text out of a [TerminalSnapshot] for selection and copy: what a
 * position holds, the word under it and the text between two positions.
 */
object TerminalText {

    /** Characters that end a word besides blanks: quotes, brackets and separators. */
    private const val WORD_BREAKS = "\"'`()[]{}<>|;,"

    /** Lines in [snap]'s document: scrollback, then screen. */
    fun lineCount(snap: TerminalSnapshot): Int = snap.scrollback.size + snap.screen.size

    /** The row holding document [line], or null when it is no longer (or not yet) in [snap]. */
    fun rowAt(snap: TerminalSnapshot, line: Long): TerminalRow? {
        val index = line - snap.droppedLines
        if (index < 0 || index >= lineCount(snap)) return null
        val i = index.toInt()
        return if (i < snap.scrollback.size) snap.scrollback[i] else snap.screen[i - snap.scrollback.size]
    }

    /** Document line of index [index] into scrollback + screen. */
    fun lineOf(snap: TerminalSnapshot, index: Int): Long = snap.droppedLines + index

    /**
     * The word around [pos], as its first and last cell, following soft-wrapped
     * rows so a long path or URL is taken whole. Null on a blank or a separator.
     */
    fun wordAt(snap: TerminalSnapshot, pos: DocPos): Pair<DocPos, DocPos>? {
        if (!isWordCell(snap, pos)) return null
        var start = pos
        while (true) {
            val prev = previous(snap, start) ?: break
            if (!isWordCell(snap, prev)) break
            start = prev
        }
        var end = pos
        while (true) {
            val next = next(snap, end) ?: break
            if (!isWordCell(snap, next)) break
            end = next
        }
        return expand(snap, start, end)
    }

    /**
     * The whole document as a selection: from the first line to the last screen
     * row in use. Null when there is nothing to select.
     */
    fun all(snap: TerminalSnapshot): Pair<DocPos, DocPos>? {
        val lastIndex = snap.scrollback.size + maxOf(1, snap.contentRows) - 1
        if (lineCount(snap) == 0) return null
        val last = minOf(lastIndex, lineCount(snap) - 1)
        val lastRow = rowAt(snap, lineOf(snap, last)) ?: return null
        return DocPos(snap.droppedLines, 0) to DocPos(lineOf(snap, last), maxOf(0, lastRow.size - 1))
    }

    /**
     * Widens [start]..[end] so neither end splits a wide glyph: a start on the
     * glyph's spacer moves to the glyph, an end on the glyph takes its spacer.
     */
    fun expand(snap: TerminalSnapshot, start: DocPos, end: DocPos): Pair<DocPos, DocPos> {
        val s = rowAt(snap, start.line)?.let { row ->
            if (start.column in 1 until row.size && row[start.column].width == 0) start.copy(column = start.column - 1) else start
        } ?: start
        val e = rowAt(snap, end.line)?.let { row ->
            if (end.column in 0 until row.size - 1 && row[end.column].width == 2) end.copy(column = end.column + 1) else end
        } ?: end
        return s to e
    }

    /**
     * The text from [start] to [end], both included. A soft-wrapped row runs on
     * into the next without a line break; any other row ends in `\n` with its
     * trailing blanks dropped. Concealed (SGR 8) cells copy as spaces.
     */
    fun text(snap: TerminalSnapshot, start: DocPos, end: DocPos): String {
        val (from, to) = if (start <= end) start to end else end to start
        val out = StringBuilder()
        var line = from.line
        while (line <= to.line) {
            val row = rowAt(snap, line)
            if (row != null) {
                val c0 = if (line == from.line) from.column.coerceAtLeast(0) else 0
                val c1 = if (line == to.line) minOf(to.column, row.size - 1) else row.size - 1
                val segment = StringBuilder()
                for (c in c0..c1) {
                    val cell = row[c]
                    if (cell.width == 0) continue
                    segment.append(if (cell.style.invisible) " " else cell.text)
                }
                if (row.wrapped && line < to.line) {
                    out.append(segment)
                } else {
                    out.append(segment.trimEnd(' '))
                    if (line < to.line) out.append('\n')
                }
            }
            line++
        }
        return out.toString()
    }

    private fun isWordCell(snap: TerminalSnapshot, pos: DocPos): Boolean {
        val cell = rowAt(snap, pos.line)?.getOrNull(pos.column) ?: return false
        if (cell.width == 0) return true // the spacer of a wide glyph
        if (cell.style.invisible) return false
        val cp = cell.codePoint
        return cp > ' '.code && (cp > 0x7E || WORD_BREAKS.indexOf(cp.toChar()) < 0)
    }

    private fun previous(snap: TerminalSnapshot, pos: DocPos): DocPos? {
        if (pos.column > 0) return pos.copy(column = pos.column - 1)
        val above = rowAt(snap, pos.line - 1) ?: return null
        return if (above.wrapped && above.isNotEmpty()) DocPos(pos.line - 1, above.size - 1) else null
    }

    private fun next(snap: TerminalSnapshot, pos: DocPos): DocPos? {
        val row = rowAt(snap, pos.line) ?: return null
        if (pos.column < row.size - 1) return pos.copy(column = pos.column + 1)
        return if (row.wrapped && rowAt(snap, pos.line + 1) != null) DocPos(pos.line + 1, 0) else null
    }
}
