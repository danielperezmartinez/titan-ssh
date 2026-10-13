package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalKeysTest {

    @Test
    fun special_keys_map_to_expected_bytes() {
        assertContentEquals(byteArrayOf(0x0D), TerminalKeys.special(TerminalKeys.SpecialKey.ENTER))
        assertContentEquals(byteArrayOf(0x7F), TerminalKeys.special(TerminalKeys.SpecialKey.BACKSPACE))
        assertContentEquals(byteArrayOf(0x09), TerminalKeys.special(TerminalKeys.SpecialKey.TAB))
        assertContentEquals(byteArrayOf(0x1B), TerminalKeys.special(TerminalKeys.SpecialKey.ESCAPE))
        assertContentEquals("[A".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.UP))
        assertContentEquals("[D".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.LEFT))
        assertContentEquals("[3~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.DELETE))
    }

    @Test
    fun ctrl_letters_produce_control_codes() {
        assertContentEquals(byteArrayOf(0x01), TerminalKeys.ctrl('a'))
        assertContentEquals(byteArrayOf(0x03), TerminalKeys.ctrl('C'))
        assertContentEquals(byteArrayOf(0x1A), TerminalKeys.ctrl('z'))
        assertContentEquals(byteArrayOf(0x1B), TerminalKeys.ctrl('['))
        assertContentEquals(byteArrayOf(0), TerminalKeys.ctrl(' '))
        assertNull(TerminalKeys.ctrl('1'))
    }

    @Test
    fun alt_prefixes_escape() {
        assertContentEquals(byteArrayOf(0x1B, 'x'.code.toByte()), TerminalKeys.alt("x"))
    }

    @Test
    fun paste_frames_text_and_turns_line_breaks_into_enter() {
        assertContentEquals("a\rb\rc".encodeToByteArray(), TerminalKeys.paste("a\r\nb\nc", bracketed = false))
        assertContentEquals("\u001B[200~a\rb\u001B[201~".encodeToByteArray(), TerminalKeys.paste("a\nb", bracketed = true))
    }

    @Test
    fun paste_keeps_only_printable_text_tabs_and_line_breaks() {
        assertContentEquals(
            "\u001B[200~echo a[201~\rid\r\u001B[201~".encodeToByteArray(),
            TerminalKeys.paste("echo a\u001B[201~\rid\r", bracketed = true),
        )
        assertContentEquals("x201~y".encodeToByteArray(), TerminalKeys.paste("x\u009B201~y", bracketed = false))
        assertEquals("a\tb\nc", TerminalKeys.pastedText("a\u0003\tb\u0004\n\u007Fc\u0000"))
        assertEquals("ñ €", TerminalKeys.pastedText("ñ €"))
    }

    @Test
    fun new_special_keys_map_to_xterm_sequences() {
        assertContentEquals("\u001B[2~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.INSERT))
        assertContentEquals("\u001B[Z".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.BACK_TAB))
        assertContentEquals("\u001BOP".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F1))
        assertContentEquals("\u001BOS".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F4))
        assertContentEquals("\u001B[15~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F5))
        assertContentEquals("\u001B[24~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F12))
    }

    @Test
    fun modifiers_on_special_keys_follow_xterm() {
        val ctrl = InputKeys.MOD_CTRL
        val shift = InputKeys.MOD_SHIFT
        val alt = InputKeys.MOD_ALT
        assertContentEquals(TerminalKeys.special(TerminalKeys.SpecialKey.LEFT), TerminalKeys.special(TerminalKeys.SpecialKey.LEFT, 0))
        assertContentEquals("\u001B[1;5D".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.LEFT, ctrl))
        assertContentEquals("\u001B[1;2A".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.UP, shift))
        assertContentEquals("\u001B[1;7H".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.HOME, ctrl or alt))
        assertContentEquals("\u001B[1;5P".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F1, ctrl))
        assertContentEquals("\u001B[3;5~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.DELETE, ctrl))
        assertContentEquals("\u001B[15;2~".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.F5, shift))
        // Shift+Tab is its own key; Alt prefixes ESC where there is no parameter.
        assertContentEquals("\u001B[Z".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.TAB, shift))
        assertContentEquals(byteArrayOf(0x1B, 0x7F), TerminalKeys.special(TerminalKeys.SpecialKey.BACKSPACE, alt))
        assertContentEquals(byteArrayOf(0x09), TerminalKeys.special(TerminalKeys.SpecialKey.TAB, ctrl))
        // Win/Super has no encoding.
        assertContentEquals("\u001B[D".encodeToByteArray(), TerminalKeys.special(TerminalKeys.SpecialKey.LEFT, InputKeys.MOD_META))
    }

    @Test
    fun typed_text_takes_the_sticky_modifiers() {
        assertContentEquals("ls".encodeToByteArray(), TerminalKeys.typed("ls", 0))
        assertContentEquals("A".encodeToByteArray(), TerminalKeys.typed("a", InputKeys.MOD_SHIFT))
        assertContentEquals(byteArrayOf(0x03), TerminalKeys.typed("c", InputKeys.MOD_CTRL))
        assertContentEquals(byteArrayOf(0x1B, 'x'.code.toByte()), TerminalKeys.typed("x", InputKeys.MOD_ALT))
        assertContentEquals(byteArrayOf(0x1B, 0x18), TerminalKeys.typed("x", InputKeys.MOD_CTRL or InputKeys.MOD_ALT))
        // No control code for a digit: it goes as typed, as before.
        assertContentEquals("1".encodeToByteArray(), TerminalKeys.typed("1", InputKeys.MOD_CTRL))
    }
}
