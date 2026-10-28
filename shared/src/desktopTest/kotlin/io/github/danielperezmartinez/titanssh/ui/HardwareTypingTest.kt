package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import io.github.danielperezmartinez.titanssh.terminal.AgentFrame
import io.github.danielperezmartinez.titanssh.terminal.InputKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// KeyEvent's factory, to build the events a keyboard sends.
@OptIn(InternalComposeUiApi::class)
class HardwareTypingTest {

    // AWT's keyChar of a key without a character (Shift, AltGr).
    private val undefined = 0xFFFF

    private fun down(key: Key, char: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, char, isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift)

    private fun typed(char: Char, ctrl: Boolean = false, alt: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, char.code, isCtrlPressed = ctrl, isAltPressed = alt)

    /** What [events] send, in order. */
    private fun HardwareTyping.send(vararg events: Any): List<AgentFrame> = events.map { it as KeyEvent }.flatMap { frames(it).orEmpty() }

    // The press of the acute dead key, as AWT reports it on Linux (ˊ).
    private val acute = down(Key.Unknown, 0x2CA)

    @Test
    fun a_dead_key_and_a_letter_type_the_composed_character() {
        // Spanish layout without an input method: ´, then u; AWT types a plain u.
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("ú")), HardwareTyping(typedEvents = true).send(acute, down(Key.U, 'u'.code), typed('u')))
        // Shift between them, for the capital.
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Text("Ú")),
            HardwareTyping(typedEvents = true).send(acute, down(Key.ShiftLeft, undefined), down(Key.U, 'U'.code, shift = true), typed('U')),
        )
        // Diaeresis (Shift+´ reports ¨), circumflex and AltGr's tilde.
        val t = HardwareTyping(typedEvents = true)
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Text("ü"), AgentFrame.Text("ê"), AgentFrame.Text("ñ")),
            t.send(down(Key.Unknown, 0xA8), typed('u'), down(Key.Unknown, 0x2C6), typed('e'), down(Key.Unknown, 0x2DC), typed('n')),
        )
    }

    @Test
    fun a_dead_key_without_a_letter_types_its_accent() {
        val t = HardwareTyping(typedEvents = true)
        // With a space, the accent alone; pressed twice, once; before a
        // letter it has no accent for, the accent and the letter.
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("´")), t.send(acute, typed(' ')))
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("´")), t.send(acute, acute))
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("´b")), t.send(acute, typed('b')))
        // A key in between that is not a character drops it.
        assertEquals(listOf<AgentFrame>(AgentFrame.Key(InputKeys.ENTER), AgentFrame.Text("a")), t.send(acute, down(Key.Enter, '\n'.code), typed('a')))
    }

    @Test
    fun accents_that_are_plain_keys_and_composing_input_methods_type_once() {
        val t = HardwareTyping(typedEvents = true)
        // A US layout's ` and ^ are characters: their typed event follows.
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("`"), AgentFrame.Text("a")), t.send(down(Key.Grave, '`'.code), typed('`'), typed('a')))
        // An input method composes the letter itself.
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("ú")), t.send(acute, typed('ú')))
    }

    @Test
    fun altgr_and_shift_presses_type_nothing_and_altgr_e_is_the_euro() {
        val frames = HardwareTyping(typedEvents = true).send(
            down(Key.AltRight, undefined),
            down(Key.E, '€'.code),
            typed('€'),
            down(Key.ShiftLeft, undefined),
            down(Key.A, 'A'.code, shift = true),
            typed('A'),
        )
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("€"), AgentFrame.Text("A")), frames)
    }

    @Test
    fun windows_altgr_reports_ctrl_alt_and_still_types_its_character() {
        val frames = HardwareTyping(typedEvents = true).send(
            down(Key.Two, '@'.code, ctrl = true, alt = true),
            typed('@', ctrl = true, alt = true),
        )
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("@")), frames)
    }

    @Test
    fun shortcuts_go_out_on_the_press_once() {
        val t = HardwareTyping(typedEvents = true)
        // Ctrl+C as its control character, then the typed event AWT sends.
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Key(InputKeys.A + 2, InputKeys.MOD_CTRL)),
            t.send(down(Key.C, 3, ctrl = true), KeyEvent(Key.Unknown, KeyEventType.Unknown, 3, isCtrlPressed = true)),
        )
        // Alt+X types its x as well: it is not sent twice.
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Key(InputKeys.A + 23, InputKeys.MOD_ALT)),
            t.send(down(Key.X, 'x'.code, alt = true), typed('x', alt = true)),
        )
        // Ctrl+Shift+T keeps its Shift.
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Key(InputKeys.A + 19, InputKeys.MOD_CTRL or InputKeys.MOD_SHIFT)),
            t.send(down(Key.T, 20, ctrl = true, shift = true)),
        )
        // The next plain character is not taken for the shortcut's.
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("ñ")), t.send(down(Key.Semicolon, 'ñ'.code), typed('ñ')))
    }

    @Test
    fun keys_without_a_character_go_out_as_keys() {
        assertEquals(
            listOf<AgentFrame>(AgentFrame.Key(InputKeys.ENTER, InputKeys.MOD_SHIFT)),
            HardwareTyping(typedEvents = true).send(down(Key.Enter, '\n'.code, shift = true), typed('\n')),
        )
    }

    @Test
    fun without_typed_events_the_press_carries_the_character() {
        // Android composes the character into the press and has no typed event.
        val t = HardwareTyping(typedEvents = false)
        assertEquals(listOf<AgentFrame>(AgentFrame.Text("ú")), t.send(down(Key.U, 'ú'.code)))
        assertNull(t.frames(down(Key.ShiftLeft, undefined)))
        assertNull(t.frames(typed('x')))
    }
}
