package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shell's directory as OSC 7 announces it, for a file browser opened from a terminal. */
class Osc7Test {

    @Test
    fun takesThePathOfAFileUri() {
        assertEquals("/home/demo/src", Osc7.path("file://host/home/demo/src"))
        assertEquals("/srv", Osc7.path("file:///srv"))
        assertEquals("/home/demo/mis cosas/ñ", Osc7.path("file://host/home/demo/mis%20cosas/%C3%B1"))
        assertEquals("/C:/Users/demo", Osc7.path("file:///C:/Users/demo"))
    }

    @Test
    fun refusesWhatIsNotAPath() {
        assertNull(Osc7.path("http://host/srv"))
        assertNull(Osc7.path("file://host"))
        assertNull(Osc7.path("file://host/bad%2"))
        assertNull(Osc7.path("file://host/bad%zz"))
        assertNull(Osc7.path("file://host/nul%00"))
    }

    @Test
    fun theEmulatorKeepsTheLastAnnouncedDirectory() {
        val emulator = TerminalEmulator(20, 4)
        assertNull(emulator.workingDirectory)
        // BEL-terminated, as bash's vte.sh sends it.
        emulator.feed("\u001b]7;file://box/home/demo\u0007$ ".encodeToByteArray())
        assertEquals("/home/demo", emulator.workingDirectory)
        // ST-terminated and split across two chunks, as fish can send it.
        emulator.feed("\u001b]7;file://box/t".encodeToByteArray())
        emulator.feed("mp\u001b\\".encodeToByteArray())
        assertEquals("/tmp", emulator.workingDirectory)
        // A title or a DCS string leaves it alone, and the screen shows no OSC.
        emulator.feed("\u001b]0;7;file://box/x\u0007\u001bP7;file://box/y\u001b\\".encodeToByteArray())
        assertEquals("/tmp", emulator.workingDirectory)
        val firstLine = emulator.snapshot().screen[0].joinToString("") { it.char.toString() }
        assertEquals("$", firstLine.trimEnd())
    }
}
