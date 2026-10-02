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
    fun default_accessory_bar_covers_required_keys() {
        val labels = DefaultAccessoryKeys.map { it.label }
        assertTrue(labels.containsAll(listOf("Esc", "Tab", "Ctrl", "Alt", "|", "/", "-", "~")))
        // The four arrows are present as send keys.
        val arrows = DefaultAccessoryKeys.filterIsInstance<AccessoryKey.Send>()
            .filter { it.label in setOf("<", ">", "^", "v") }
        assertTrue(arrows.size == 4)
    }
}
