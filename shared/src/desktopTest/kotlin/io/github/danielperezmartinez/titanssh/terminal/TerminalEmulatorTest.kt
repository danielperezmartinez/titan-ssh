package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalEmulatorTest {

    private fun bytes(s: String): ByteArray = s.encodeToByteArray()

    @Test
    fun writes_printable_text_at_origin() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("hi"))
        val s = e.snapshot()
        assertEquals('h', s.screen[0][0].char)
        assertEquals('i', s.screen[0][1].char)
        assertEquals(2, s.cursorColumn)
        assertEquals(0, s.cursorRow)
    }

    @Test
    fun carriage_return_and_line_feed() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("ab\r\nc"))
        val s = e.snapshot()
        assertEquals('a', s.screen[0][0].char)
        assertEquals('c', s.screen[1][0].char)
        assertEquals(1, s.cursorRow)
    }

    @Test
    fun backspace_moves_cursor_left() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("ab"))
        e.feed(byteArrayOf(0x08)) // BS
        assertEquals(1, e.snapshot().cursorColumn)
    }

    @Test
    fun wraps_at_the_right_margin() {
        val e = TerminalEmulator(3, 3)
        e.feed(bytes("abcd"))
        val s = e.snapshot()
        assertEquals('a', s.screen[0][0].char)
        assertEquals('c', s.screen[0][2].char)
        assertEquals('d', s.screen[1][0].char)
    }

    @Test
    fun scrolls_and_fills_scrollback() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("a\r\nb\r\nc\r\nd"))
        val s = e.snapshot()
        assertEquals(1, s.scrollback.size)
        assertEquals('a', s.scrollback[0][0].char)
        assertEquals('b', s.screen[0][0].char)
        assertEquals('d', s.screen[2][0].char)
    }

    @Test
    fun csi_cursor_position_then_write() {
        val e = TerminalEmulator(10, 5)
        e.feed(bytes("[2;3HX"))
        val s = e.snapshot()
        assertEquals('X', s.screen[1][2].char)
    }

    @Test
    fun sgr_sets_and_resets_foreground() {
        val e = TerminalEmulator(10, 2)
        e.feed(bytes("[31mR[0mN"))
        val s = e.snapshot()
        assertEquals(TermColor.Indexed(1), s.screen[0][0].fg)
        assertEquals(TermColor.Default, s.screen[0][1].fg)
    }

    @Test
    fun sgr_true_color_and_bright() {
        val e = TerminalEmulator(10, 2)
        e.feed(bytes("[38;2;10;20;30mT[93mB"))
        val s = e.snapshot()
        assertEquals(TermColor.Rgb(10, 20, 30), s.screen[0][0].fg)
        assertEquals(TermColor.Indexed(11), s.screen[0][1].fg) // 93 -> bright yellow (3+8)
    }

    @Test
    fun erase_whole_line() {
        val e = TerminalEmulator(10, 2)
        e.feed(bytes("abc[2K"))
        val s = e.snapshot()
        assertEquals(' ', s.screen[0][0].char)
        assertEquals(' ', s.screen[0][2].char)
    }

    @Test
    fun osc_sequence_is_ignored() {
        val e = TerminalEmulator(10, 2)
        e.feed(bytes("]0;window titleX"))
        val s = e.snapshot()
        assertEquals('X', s.screen[0][0].char)
        assertEquals(1, s.cursorColumn)
    }

    @Test
    fun decodes_multibyte_utf8() {
        val e = TerminalEmulator(10, 2)
        e.feed(byteArrayOf(0xC3.toByte(), 0xA9.toByte())) // 'é'
        assertEquals('é', e.snapshot().screen[0][0].char)
    }

    @Test
    fun carries_incomplete_utf8_across_feeds() {
        val e = TerminalEmulator(10, 2)
        e.feed(byteArrayOf(0xC3.toByte())) // lead only
        assertEquals(' ', e.snapshot().screen[0][0].char)
        e.feed(byteArrayOf(0xA9.toByte())) // continuation
        assertEquals('é', e.snapshot().screen[0][0].char)
    }

    @Test
    fun resize_preserves_top_left_content() {
        val e = TerminalEmulator(80, 24)
        e.feed(bytes("hello"))
        e.resize(3, 5)
        val s = e.snapshot()
        assertEquals(3, s.columns)
        assertEquals(5, s.rows)
        assertEquals('h', s.screen[0][0].char)
        assertEquals('l', s.screen[0][2].char)
    }

    /** Prepends ESC so a CSI/escape can be written from a plain string. */
    private fun esc(s: String): ByteArray = bytes("$s")

    @Test
    fun alternate_screen_isolates_content_and_restores_main() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("main"))            // main screen, cursor at col 4
        e.feed(esc("[?1049h"))           // enter alt (blank), saving the cursor
        e.feed(bytes("ALT"))
        var s = e.snapshot()
        assertEquals('A', s.screen[0][0].char, "alt buffer shows its own content")
        assertEquals(0, s.scrollback.size, "the alt screen keeps no scrollback")

        e.feed(esc("[?1049l"))           // leave alt: main screen + cursor restored
        s = e.snapshot()
        assertEquals('m', s.screen[0][0].char, "main content is back")
        assertEquals('n', s.screen[0][3].char)
        assertEquals(4, s.cursorColumn, "the saved cursor is restored")
    }

    @Test
    fun alt_screen_scrolling_does_not_grow_scrollback() {
        val e = TerminalEmulator(10, 2)
        e.feed(esc("[?1049h"))
        e.feed(bytes("a\r\nb\r\nc"))      // scrolls within the alt buffer
        assertEquals(0, e.snapshot().scrollback.size)
    }

    @Test
    fun scroll_region_confines_line_feed() {
        val e = TerminalEmulator(4, 4)
        e.feed(esc("[1;1H")); e.feed(bytes("A"))
        e.feed(esc("[2;1H")); e.feed(bytes("B"))
        e.feed(esc("[3;1H")); e.feed(bytes("C"))
        e.feed(esc("[4;1H")); e.feed(bytes("D"))
        e.feed(esc("[2;3r"))              // region = rows 2..3 (index 1..2)
        e.feed(esc("[3;1H"))              // cursor at the bottom margin (index 2)
        e.feed(bytes("\n"))               // LF scrolls only the region up
        val s = e.snapshot()
        assertEquals('A', s.screen[0][0].char, "row above the region is untouched")
        assertEquals('C', s.screen[1][0].char, "B evicted, C moved up inside region")
        assertEquals(' ', s.screen[2][0].char, "blank enters at the region bottom")
        assertEquals('D', s.screen[3][0].char, "row below the region is untouched")
        assertEquals(0, s.scrollback.size, "a region scroll below the top feeds no scrollback")
    }

    @Test
    fun insert_and_delete_lines_within_region() {
        fun filled(): TerminalEmulator {
            val e = TerminalEmulator(4, 4)
            e.feed(esc("[1;1H")); e.feed(bytes("A"))
            e.feed(esc("[2;1H")); e.feed(bytes("B"))
            e.feed(esc("[3;1H")); e.feed(bytes("C"))
            e.feed(esc("[4;1H")); e.feed(bytes("D"))
            return e
        }
        val il = filled()
        il.feed(esc("[2;1H")); il.feed(esc("[L")) // insert a line at row 2
        var s = il.snapshot()
        assertEquals('A', s.screen[0][0].char)
        assertEquals(' ', s.screen[1][0].char)
        assertEquals('B', s.screen[2][0].char)
        assertEquals('C', s.screen[3][0].char) // D pushed off the bottom

        val dl = filled()
        dl.feed(esc("[2;1H")); dl.feed(esc("[M")) // delete row 2
        s = dl.snapshot()
        assertEquals('A', s.screen[0][0].char)
        assertEquals('C', s.screen[1][0].char)
        assertEquals('D', s.screen[2][0].char)
        assertEquals(' ', s.screen[3][0].char)
    }

    @Test
    fun insert_delete_erase_chars() {
        val ich = TerminalEmulator(6, 1)
        ich.feed(bytes("abcd")); ich.feed(esc("[1;3H")); ich.feed(esc("[@"))
        var s = ich.snapshot()
        assertEquals(' ', s.screen[0][2].char)
        assertEquals('c', s.screen[0][3].char)

        val dch = TerminalEmulator(6, 1)
        dch.feed(bytes("abcd")); dch.feed(esc("[1;3H")); dch.feed(esc("[P"))
        s = dch.snapshot()
        assertEquals('d', s.screen[0][2].char)

        val ech = TerminalEmulator(6, 1)
        ech.feed(bytes("abcd")); ech.feed(esc("[1;2H")); ech.feed(esc("[2X"))
        s = ech.snapshot()
        assertEquals('a', s.screen[0][0].char)
        assertEquals(' ', s.screen[0][1].char)
        assertEquals(' ', s.screen[0][2].char)
        assertEquals('d', s.screen[0][3].char)
    }

    @Test
    fun scroll_up_feeds_scrollback_on_full_screen() {
        val e = TerminalEmulator(4, 3)
        e.feed(esc("[1;1H")); e.feed(bytes("A"))
        e.feed(esc("[2;1H")); e.feed(bytes("B"))
        e.feed(esc("[3;1H")); e.feed(bytes("C"))
        e.feed(esc("[S")) // SU: whole-screen scroll up, top row goes to scrollback
        val s = e.snapshot()
        assertEquals(1, s.scrollback.size)
        assertEquals('A', s.scrollback[0][0].char)
        assertEquals('B', s.screen[0][0].char)
        assertEquals(' ', s.screen[2][0].char)
    }

    private fun TerminalEmulator.text(): List<String> {
        val s = snapshot()
        return (s.scrollback + s.screen).map { row -> row.filter { it.width != 0 }.joinToString("") { it.text }.trimEnd() }
    }

    @Test
    fun narrowing_reflows_a_long_line_instead_of_cutting_it() {
        val e = TerminalEmulator(10, 4)
        e.feed(bytes("0123456789abcdefghij\r\n$ "))
        e.resize(5, 4)
        assertEquals(listOf("01234", "56789", "abcde", "fghij", "$"), e.text().filter { it.isNotEmpty() })
        val s = e.snapshot()
        assertEquals(2, s.cursorColumn, "the cursor stays after the prompt")
        assertEquals("$", s.screen[s.cursorRow].joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun widening_rejoins_soft_wrapped_lines() {
        val e = TerminalEmulator(5, 4)
        e.feed(bytes("0123456789\r\nok"))
        e.resize(12, 4)
        val s = e.snapshot()
        assertEquals("0123456789", s.screen[0].joinToString("") { it.text }.trimEnd())
        assertEquals("ok", s.screen[1].joinToString("") { it.text }.trimEnd())
        assertEquals(1, s.cursorRow)
        assertEquals(2, s.cursorColumn)
    }

    @Test
    fun shorter_screen_pushes_rows_to_scrollback_and_keeps_the_cursor() {
        val e = TerminalEmulator(10, 6)
        e.feed(bytes("a\r\nb\r\nc\r\nd\r\ne\r\n$ "))
        e.resize(10, 3)
        val s = e.snapshot()
        assertEquals(listOf("a", "b", "c"), s.scrollback.map { r -> r.joinToString("") { it.text }.trimEnd() })
        assertEquals("$", s.screen[2].joinToString("") { it.text }.trimEnd())
        assertEquals(2, s.cursorRow)

        e.resize(10, 6) // taller again: the pushed rows come back
        val back = e.snapshot()
        assertEquals(0, back.scrollback.size)
        assertEquals('a', back.screen[0][0].char)
        assertEquals(5, back.cursorRow)
    }

    @Test
    fun wide_glyph_takes_two_cells_and_wraps_whole() {
        val e = TerminalEmulator(5, 2)
        e.feed(bytes("abcd漢"))
        val s = e.snapshot()
        assertEquals(' ', s.screen[0][4].char, "no room for both halves: padded")
        assertEquals('漢'.code, s.screen[1][0].codePoint)
        assertEquals(2, s.screen[1][0].width)
        assertEquals(0, s.screen[1][1].width)
        assertEquals(2, s.cursorColumn)
    }

    @Test
    fun decodes_code_points_beyond_the_bmp() {
        val e = TerminalEmulator(10, 1)
        e.feed(bytes("🚀x")) // 🚀
        val s = e.snapshot()
        assertEquals(0x1F680, s.screen[0][0].codePoint)
        assertEquals("🚀", s.screen[0][0].text)
        assertEquals('x', s.screen[0][2].char)
    }

    @Test
    fun cursor_visibility_and_modes_are_reported() {
        val e = TerminalEmulator(10, 2)
        e.feed(esc("[?25l")); e.feed(esc("[?1h")); e.feed(esc("[?2004h"))
        var s = e.snapshot()
        assertEquals(false, s.cursorVisible)
        assertEquals(true, s.applicationCursorKeys)
        assertEquals(true, s.bracketedPaste)
        e.feed(esc("[?25h"))
        s = e.snapshot()
        assertEquals(true, s.cursorVisible)
    }

    @Test
    fun answers_cursor_position_and_device_attribute_queries() {
        val e = TerminalEmulator(10, 5)
        e.feed(esc("[3;4H")); e.feed(esc("[6n")); e.feed(esc("[c"))
        assertEquals("\u001B[3;4R\u001B[?1;2c", e.takeResponses()?.decodeToString())
        assertEquals(null, e.takeResponses())
    }

    @Test
    fun last_column_write_defers_the_wrap() {
        val e = TerminalEmulator(3, 2)
        e.feed(bytes("abc"))
        var s = e.snapshot()
        assertEquals(0, s.cursorRow, "writing the last column does not wrap yet")
        e.feed(bytes("\r\n"))
        s = e.snapshot()
        assertEquals(1, s.cursorRow, "so CR LF after a full line leaves no blank line")
    }

    @Test
    fun erases_matching_lines_but_not_the_cursor_line() {
        val e = TerminalEmulator(20, 5)
        e.feed(bytes("keep\r\n$ echo __TOK__\r\n__TOK__:0\r\n$ "))
        val pattern = Regex("__TOK__")
        assertEquals(false, e.eraseLinesMatching(pattern))
        assertEquals(listOf("keep", "$"), e.text().filter { it.isNotEmpty() })
        assertEquals(1, e.snapshot().cursorRow)

        e.feed(bytes("typing __TOK__"))
        assertEquals(true, e.eraseLinesMatching(pattern), "the cursor line waits")
        assertEquals("$ typing __TOK__", e.text().last { it.isNotEmpty() })
    }

    @Test
    fun erasing_a_line_pulls_scrollback_down_on_a_full_screen() {
        val e = TerminalEmulator(10, 3)
        e.feed(bytes("old\r\nx __TOK__\r\nnew\r\n$ "))
        assertEquals(1, e.snapshot().scrollback.size)
        e.eraseLinesMatching(Regex("__TOK__"))
        val s = e.snapshot()
        assertEquals(0, s.scrollback.size)
        assertEquals(listOf("old", "new", "$"), s.screen.map { r -> r.joinToString("") { it.text }.trimEnd() })
        assertEquals(2, s.cursorRow)
    }
}
