package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TerminalTextTest {

    private fun emulator(cols: Int, rows: Int, text: String, maxScrollback: Int = 2000): TerminalEmulator =
        TerminalEmulator(cols, rows, maxScrollback).apply { feed(text.encodeToByteArray()) }

    @Test
    fun copies_rows_with_line_breaks_and_without_trailing_blanks() {
        val s = emulator(10, 3, "ls -l\r\nfoo bar").snapshot()
        assertEquals("ls -l\nfoo bar", TerminalText.text(s, DocPos(0, 0), DocPos(1, 9)))
        assertEquals("-l\nfoo", TerminalText.text(s, DocPos(0, 3), DocPos(1, 2)))
    }

    @Test
    fun selection_order_does_not_matter() {
        val s = emulator(10, 3, "ls -l\r\nfoo bar").snapshot()
        assertEquals(TerminalText.text(s, DocPos(0, 3), DocPos(1, 2)), TerminalText.text(s, DocPos(1, 2), DocPos(0, 3)))
    }

    @Test
    fun soft_wrapped_rows_copy_as_one_line() {
        val s = emulator(5, 3, "abcdefgh ij").snapshot()
        // "abcde" wraps into "fgh i" and then "j".
        assertEquals("abcdefgh ij", TerminalText.text(s, DocPos(0, 0), DocPos(2, 4)))
    }

    @Test
    fun wide_glyph_copies_once_and_is_never_split() {
        val s = emulator(10, 2, "a日b").snapshot()
        assertEquals("a日b", TerminalText.text(s, DocPos(0, 0), DocPos(0, 9)))
        // A start on the glyph's spacer moves to the glyph; an end on the glyph takes its spacer.
        assertEquals(DocPos(0, 1) to DocPos(0, 2), TerminalText.expand(s, DocPos(0, 2), DocPos(0, 1)))
        assertEquals("日", TerminalText.text(s, DocPos(0, 1), DocPos(0, 1)))
    }

    @Test
    fun concealed_cells_copy_as_spaces() {
        val s = emulator(20, 2, "pw:\u001B[8msecret\u001B[0m!").snapshot()
        assertEquals("pw:      !", TerminalText.text(s, DocPos(0, 0), DocPos(0, 19)))
    }

    @Test
    fun word_under_a_position_stops_at_blanks_and_separators() {
        val s = emulator(40, 2, "cat \"/etc/hosts\" | less").snapshot()
        assertEquals(DocPos(0, 5) to DocPos(0, 14), TerminalText.wordAt(s, DocPos(0, 8)))
        assertEquals(DocPos(0, 0) to DocPos(0, 2), TerminalText.wordAt(s, DocPos(0, 1)))
        assertNull(TerminalText.wordAt(s, DocPos(0, 3)))
        assertNull(TerminalText.wordAt(s, DocPos(0, 17)))
    }

    @Test
    fun word_follows_soft_wraps() {
        val s = emulator(10, 3, "go https://example.org/x ok").snapshot()
        val word = TerminalText.wordAt(s, DocPos(1, 2))!!
        assertEquals("https://example.org/x", TerminalText.text(s, word.first, word.second))
    }

    @Test
    fun positions_survive_the_scrollback_cap() {
        // Two screen rows and two of scrollback: "one" and "two" scroll off.
        val e = emulator(10, 2, "one\r\ntwo\r\nthree\r\n", maxScrollback = 2)
        val before = e.snapshot()
        assertEquals(0L, before.droppedLines)
        assertEquals("three", TerminalText.text(before, DocPos(2, 0), DocPos(2, 9)))
        // "four" and "five" push "three" and "four" into the scrollback; the cap drops "one" and "two".
        e.feed("four\r\nfive\r\n".encodeToByteArray())
        val after = e.snapshot()
        assertEquals(2L, after.droppedLines)
        assertEquals(before.layoutEpoch, after.layoutEpoch)
        assertNull(TerminalText.rowAt(after, 1))
        assertEquals("three", TerminalText.text(after, DocPos(2, 0), DocPos(2, 9)))
        assertEquals("five", TerminalText.text(after, DocPos(4, 0), DocPos(4, 9)))
    }

    @Test
    fun rewriting_lines_changes_the_layout_epoch() {
        val e = emulator(10, 3, "hello\r\n")
        val epoch = e.snapshot().layoutEpoch
        e.feed("more\r\n".encodeToByteArray())
        assertEquals(epoch, e.snapshot().layoutEpoch)
        e.resize(8, 3)
        val resized = e.snapshot().layoutEpoch
        assertNotEquals(epoch, resized)
        e.feed("\u001B[3J".encodeToByteArray())
        assertNotEquals(resized, e.snapshot().layoutEpoch)
    }

    @Test
    fun select_all_spans_scrollback_to_the_last_used_row() {
        val s = emulator(10, 3, "a\r\nb\r\nc\r\nd").snapshot()
        val (from, to) = TerminalText.all(s)!!
        assertEquals("a\nb\nc\nd", TerminalText.text(s, from, to))
    }
}
