package io.github.danielperezmartinez.titanssh.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.danielperezmartinez.titanssh.ui.insertionIndex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyBarTest {

    @Test
    fun catalog_ids_are_unique_and_the_default_bar_is_in_it() {
        val ids = KeyBar.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        KeyBar.defaultIds.forEach { assertNotNull(KeyBar.key(it), it) }
    }

    @Test
    fun default_bar_keeps_the_terminal_keys() {
        val labels = KeyBar.resolve(null).map { it.label }
        assertEquals(
            listOf("Esc", "Tab", "Ctrl", "Alt", "Mayús", "←", "↓", "↑", "→", "|", "/", "-", "~", "Pegar"),
            labels,
        )
    }

    @Test
    fun saved_bar_skips_unknown_ids_and_repeats() {
        val bar = KeyBar.resolve(listOf("f5", "from-a-newer-build", "esc", "f5"))
        assertEquals(listOf("f5", "esc"), bar.map { it.id })
        assertTrue(KeyBar.resolve(emptyList()).isEmpty())
    }

    @Test
    fun keys_reach_the_terminal_as_bytes() {
        fun bytes(id: String, mods: Int = 0) = KeyBar.key(id)!!.terminalBytes(mods)
        assertContentEquals(byteArrayOf(0x1B), bytes("esc"))
        assertContentEquals("\u001B[1;5C".encodeToByteArray(), bytes("right", InputKeys.MOD_CTRL))
        assertContentEquals("\u001B[Z".encodeToByteArray(), bytes("backtab"))
        assertContentEquals(byteArrayOf(0x03), bytes("ctrl-c"))
        assertContentEquals(byteArrayOf(0x1B, 0x12), bytes("ctrl-r", InputKeys.MOD_ALT))
        assertContentEquals("|".encodeToByteArray(), bytes("sym:|"))
        // Ctrl + a symbol from the bar is a control code, as from the keyboard.
        assertContentEquals(byteArrayOf(0x1C), bytes("sym:\\", InputKeys.MOD_CTRL))
        assertNull(bytes("ctrl"))
        assertNull(bytes("paste"))
        assertNull(bytes("prtsc"))
    }

    @Test
    fun keys_reach_the_desktop_as_frames() {
        fun frames(id: String, mods: Int = 0) = KeyBar.key(id)!!.desktopFrames(mods)
        assertEquals(listOf(AgentFrame.Key(InputKeys.ESCAPE, 0)), frames("esc"))
        assertEquals(listOf(AgentFrame.Key(InputKeys.TAB, InputKeys.MOD_SHIFT)), frames("backtab"))
        assertEquals(listOf(AgentFrame.Key(InputKeys.function(5), InputKeys.MOD_ALT)), frames("f5", InputKeys.MOD_ALT))
        assertEquals(listOf(AgentFrame.Key(InputKeys.A + 2, InputKeys.MOD_CTRL)), frames("ctrl-c"))
        assertEquals(listOf(AgentFrame.Text("~")), frames("sym:~"))
        assertEquals(listOf(AgentFrame.Key(InputKeys.PRINT_SCREEN, 0)), frames("prtsc"))
        assertNull(frames("shift"))
    }

    @Test
    fun only_desktop_keys_are_off_in_the_terminal() {
        val off = KeyBar.catalog.filterNot { it.inTerminal }.map { it.id }
        assertEquals(listOf("win", "menu", "prtsc", "vol-down", "vol-up", "mute", "play", "prev", "next"), off)
        assertFalse(KeyBar.key("win")!!.inTerminal)
        assertTrue(KeyBar.key("paste")!!.inTerminal)
    }

    @Test
    fun drop_position_follows_wrapped_rows() {
        // Two rows of two chips: [0][1] / [2][3], 40 wide, 20 high.
        val rects = listOf(
            Rect(0f, 0f, 40f, 20f), Rect(50f, 0f, 90f, 20f),
            Rect(0f, 30f, 40f, 50f), Rect(50f, 30f, 90f, 50f),
        )
        assertEquals(0, insertionIndex(rects, Offset(5f, 10f)))
        assertEquals(1, insertionIndex(rects, Offset(30f, 10f)))
        assertEquals(2, insertionIndex(rects, Offset(200f, 10f)))
        assertEquals(2, insertionIndex(rects, Offset(5f, 40f)))
        assertEquals(4, insertionIndex(rects, Offset(85f, 40f)))
        assertEquals(4, insertionIndex(rects, Offset(5f, 80f)))
        assertEquals(0, insertionIndex(emptyList(), Offset(5f, 5f)))
    }

    @Test
    fun paste_on_the_desktop_types_lines_with_enter() {
        assertEquals(
            listOf(AgentFrame.Text("a"), AgentFrame.Key(InputKeys.ENTER), AgentFrame.Text("b")),
            MousepadTyping.pasted("a\r\nb"),
        )
        assertEquals(listOf(AgentFrame.Text("xy")), MousepadTyping.pasted("x\u0003y"))
    }
}
