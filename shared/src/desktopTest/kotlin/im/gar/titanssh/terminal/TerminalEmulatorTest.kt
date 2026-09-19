package im.gar.titanssh.terminal

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
}
