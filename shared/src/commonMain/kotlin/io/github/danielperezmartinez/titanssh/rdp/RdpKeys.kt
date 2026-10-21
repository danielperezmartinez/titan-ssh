package io.github.danielperezmartinez.titanssh.rdp

import io.github.danielperezmartinez.titanssh.terminal.AgentFrame
import io.github.danielperezmartinez.titanssh.terminal.InputKeys

/**
 * The mouse pad's input ([AgentFrame] with [InputKeys], ADR-0016) as RDP
 * input, so the RDP view reuses the key bar, the system keyboard capture and
 * the touchpad gestures as they are. Keys become PC/AT set-1 scancodes, the
 * position the server's own layout gives meaning to; text goes as Unicode.
 */
object RdpKeys {
    const val SHIFT = 0x2A
    const val CTRL = 0x1D
    const val ALT = 0x38
    const val META = 0xE05B

    /** The scancode of an [InputKeys] key, or null for one RDP has no key for. */
    fun scancode(key: Int): Int? = when (key) {
        InputKeys.ENTER -> 0x1C
        InputKeys.ESCAPE -> 0x01
        InputKeys.BACKSPACE -> 0x0E
        InputKeys.TAB -> 0x0F
        InputKeys.SPACE -> 0x39
        InputKeys.DELETE -> 0xE053
        InputKeys.INSERT -> 0xE052
        InputKeys.HOME -> 0xE047
        InputKeys.END -> 0xE04F
        InputKeys.PAGE_UP -> 0xE049
        InputKeys.PAGE_DOWN -> 0xE051
        InputKeys.ARROW_LEFT -> 0xE04B
        InputKeys.ARROW_RIGHT -> 0xE04D
        InputKeys.ARROW_UP -> 0xE048
        InputKeys.ARROW_DOWN -> 0xE050
        in InputKeys.F1..InputKeys.F1 + 9 -> 0x3B + (key - InputKeys.F1)
        InputKeys.F1 + 10 -> 0x57
        InputKeys.F12 -> 0x58
        InputKeys.PRINT_SCREEN -> 0xE037
        InputKeys.CONTEXT_MENU -> 0xE05D
        InputKeys.VOLUME_UP -> 0xE030
        InputKeys.VOLUME_DOWN -> 0xE02E
        InputKeys.VOLUME_MUTE -> 0xE020
        InputKeys.MEDIA_PLAY_PAUSE -> 0xE022
        InputKeys.MEDIA_NEXT -> 0xE019
        InputKeys.MEDIA_PREVIOUS -> 0xE010
        in InputKeys.A..InputKeys.Z -> LETTERS[key - InputKeys.A]
        InputKeys.DIGIT_0 -> 0x0B
        in InputKeys.DIGIT_0 + 1..InputKeys.DIGIT_9 -> 0x02 + (key - InputKeys.DIGIT_0 - 1)
        InputKeys.SHIFT -> SHIFT
        InputKeys.CTRL -> CTRL
        InputKeys.ALT -> ALT
        InputKeys.META -> META
        else -> null
    }

    /** Set-1 scancodes of A..Z, on the keys of a US layout. */
    private val LETTERS = intArrayOf(
        0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
        0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,
    )

    /**
     * The key that types [c] on any Latin layout, and whether it takes Shift:
     * ASCII letters and digits, space, Enter and Tab. Null for the rest, whose
     * key depends on the server's layout and go as Unicode.
     */
    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> LETTERS[c - 'a'] to false
        in 'A'..'Z' -> LETTERS[c - 'A'] to true
        '0' -> 0x0B to false
        in '1'..'9' -> 0x02 + (c - '1') to false
        ' ' -> 0x39 to false
        '\n', '\r' -> 0x1C to false
        '\t' -> 0x0F to false
        else -> null
    }

    /** The modifier keys of `MOD_*` [modifiers], in the order they go down. */
    fun modifierScancodes(modifiers: Int): List<Int> = buildList {
        if (modifiers and InputKeys.MOD_CTRL != 0) add(CTRL)
        if (modifiers and InputKeys.MOD_ALT != 0) add(ALT)
        if (modifiers and InputKeys.MOD_SHIFT != 0) add(SHIFT)
        if (modifiers and InputKeys.MOD_META != 0) add(META)
    }

    /** The RDP mouse button of an [InputKeys] `BUTTON_*`. */
    fun button(button: Int): Int = when (button) {
        InputKeys.BUTTON_RIGHT -> 2
        InputKeys.BUTTON_MIDDLE -> 1
        else -> 0
    }
}

/**
 * Sends one mouse pad [frame] to the desktop. A relative pointer move moves
 * the pointer from where it is ([RdpScreen.moveBy]).
 */
fun RdpScreen.send(frame: AgentFrame) {
    when (frame) {
        is AgentFrame.Key -> {
            val code = RdpKeys.scancode(frame.key) ?: return
            val mods = RdpKeys.modifierScancodes(frame.modifiers)
            if (frame.action != InputKeys.ACTION_UP) {
                mods.forEach { key(it, true) }
                key(code, true)
            }
            if (frame.action != InputKeys.ACTION_DOWN) {
                key(code, false)
                mods.asReversed().forEach { key(it, false) }
            }
        }
        is AgentFrame.Text -> text(frame.text)
        is AgentFrame.PointerButton -> mouseButton(RdpKeys.button(frame.button), frame.pressed)
        is AgentFrame.PointerMove -> moveBy(frame.dx, frame.dy)
        is AgentFrame.Scroll -> {
            wheelSteps(frame.dy).forEach { wheel(vertical = true, units = it) }
            wheelSteps(frame.dx).forEach { wheel(vertical = false, units = it) }
        }
        else -> Unit
    }
}

/** [units] (1/120 of a notch) in steps RDP can carry: at most one notch each. */
internal fun wheelSteps(units: Int): List<Int> = buildList {
    var rest = units
    while (rest != 0) {
        val step = rest.coerceIn(-WHEEL_NOTCH, WHEEL_NOTCH)
        add(step)
        rest -= step
    }
}

private const val WHEEL_NOTCH = 120
