package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalWheelTest {

    private fun emulator(vararg output: String) = TerminalEmulator(45, 40).apply {
        output.forEach { feed(it.encodeToByteArray()) }
    }

    @Test
    fun emulator_tracks_mouse_modes() {
        val e = emulator("\u001B[?1000h\u001B[?1002h\u001B[?1003h\u001B[?1006h")
        assertTrue(e.snapshot().mouseTracking)
        assertTrue(e.snapshot().sgrMouse)
        // Turning off one mode leaves the others asking for reports.
        e.feed("\u001B[?1003l\u001B[?1002l".encodeToByteArray())
        assertTrue(e.snapshot().mouseTracking)
        e.feed("\u001B[?1000l\u001B[?1006l".encodeToByteArray())
        assertFalse(e.snapshot().mouseTracking)
        assertFalse(e.snapshot().sgrMouse)
    }

    @Test
    fun reset_clears_mouse_modes() {
        val e = emulator("\u001B[?1000;1006h\u001B[?1007l", "\u001Bc")
        val s = e.snapshot()
        assertFalse(s.mouseTracking)
        assertFalse(s.sgrMouse)
        assertTrue(s.alternateScroll)
    }

    @Test
    fun main_screen_without_mouse_scrolls_locally() {
        assertNull(TerminalWheel.bytes(emulator("$ ls\r\n").snapshot(), 1, 0, 0))
    }

    @Test
    fun claude_fullscreen_gets_sgr_wheel_reports() {
        // What Claude Code's full-screen mode sends on start.
        val s = emulator("\u001B[?1049h\u001B[2J\u001B[H\u001B[?1000h\u001B[?1002h\u001B[?1003h\u001B[?1006h").snapshot()
        assertContentEquals("\u001B[<64;1;21M\u001B[<64;1;21M".encodeToByteArray(), TerminalWheel.bytes(s, 2, 0, 20))
        assertContentEquals("\u001B[<65;3;1M".encodeToByteArray(), TerminalWheel.bytes(s, -1, 2, 0))
    }

    @Test
    fun x10_wheel_report_without_sgr() {
        val s = emulator("\u001B[?1000h").snapshot()
        val expected = byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (32 + 64).toByte(), (32 + 1).toByte(), (32 + 5).toByte())
        assertContentEquals(expected, TerminalWheel.bytes(s, 1, 0, 4))
    }

    @Test
    fun alternate_screen_without_mouse_sends_arrows() {
        val e = emulator("\u001B[?1049h")
        assertContentEquals("\u001B[A\u001B[A\u001B[A".encodeToByteArray(), TerminalWheel.bytes(e.snapshot(), 3, 0, 0))
        e.feed("\u001B[?1h".encodeToByteArray()) // DECCKM
        assertContentEquals("\u001BOB".encodeToByteArray(), TerminalWheel.bytes(e.snapshot(), -1, 0, 0))
        e.feed("\u001B[?1007l".encodeToByteArray())
        assertNull(TerminalWheel.bytes(e.snapshot(), 1, 0, 0))
    }

    @Test
    fun fastfetch_greeting_stays_in_the_main_scrollback() {
        // The greeting fills the scrollback; the full-screen app's screen starts blank.
        val greeting = (1..60).joinToString("") { "fastfetch line $it\r\n" }
        val s = emulator(greeting, "\u001B[?1049h\u001B[2J\u001B[H\u001B[?1000h\u001B[?1006h").snapshot()
        assertTrue(s.altScreen)
        assertTrue(TerminalWheel.goesToRemote(s))
        assertEquals(true, s.screen.all { row -> row.all { it.isBlank } })
    }
}
