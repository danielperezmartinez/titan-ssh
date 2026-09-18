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
}
