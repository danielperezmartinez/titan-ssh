package io.github.danielperezmartinez.titanssh.terminal

/**
 * Pure translation of key presses to the byte sequences a remote PTY expects.
 * Kept free of Compose/AWT key types so it is unit-testable; the UI layer maps
 * its platform key events onto [SpecialKey] and printable text and calls here.
 */
object TerminalKeys {

    /** Named non-text keys that map to control bytes or escape sequences. */
    enum class SpecialKey {
        ENTER, BACKSPACE, TAB, ESCAPE, DELETE,
        UP, DOWN, RIGHT, LEFT,
        HOME, END, PAGE_UP, PAGE_DOWN,
        INSERT,

        /** Shift+Tab, which terminals send as its own sequence. */
        BACK_TAB,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    }

    /** Encodes printable [text] typed by the user as UTF-8 bytes. */
    fun text(text: String): ByteArray = text.encodeToByteArray()

    /** Encodes a [SpecialKey] to its terminal byte sequence (normal cursor mode). */
    fun special(key: SpecialKey): ByteArray = when (key) {
        SpecialKey.ENTER -> byteArrayOf(0x0D) // CR
        SpecialKey.BACKSPACE -> byteArrayOf(0x7F) // DEL, the common OpenSSH default
        SpecialKey.TAB -> byteArrayOf(0x09)
        SpecialKey.ESCAPE -> byteArrayOf(0x1B)
        SpecialKey.DELETE -> csi("3~")
        SpecialKey.UP -> csi("A")
        SpecialKey.DOWN -> csi("B")
        SpecialKey.RIGHT -> csi("C")
        SpecialKey.LEFT -> csi("D")
        SpecialKey.HOME -> csi("H")
        SpecialKey.END -> csi("F")
        SpecialKey.PAGE_UP -> csi("5~")
        SpecialKey.PAGE_DOWN -> csi("6~")
        SpecialKey.INSERT -> csi("2~")
        SpecialKey.BACK_TAB -> csi("Z")
        SpecialKey.F1 -> ss3("P")
        SpecialKey.F2 -> ss3("Q")
        SpecialKey.F3 -> ss3("R")
        SpecialKey.F4 -> ss3("S")
        SpecialKey.F5 -> csi("15~")
        SpecialKey.F6 -> csi("17~")
        SpecialKey.F7 -> csi("18~")
        SpecialKey.F8 -> csi("19~")
        SpecialKey.F9 -> csi("20~")
        SpecialKey.F10 -> csi("21~")
        SpecialKey.F11 -> csi("23~")
        SpecialKey.F12 -> csi("24~")
    }

    /**
     * [key] pressed with [modifiers] (InputKeys `MOD_*` bits), as xterm sends
     * it: the cursor, editing and function keys carry the modifiers as a
     * parameter (`Ctrl+Left` = `ESC [ 1 ; 5 D`), Shift+Tab is [SpecialKey.BACK_TAB],
     * and the rest only take Alt, as an ESC prefix. Win/Super has no encoding
     * and is ignored.
     */
    fun special(key: SpecialKey, modifiers: Int): ByteArray {
        val shift = modifiers and InputKeys.MOD_SHIFT != 0
        val alt = modifiers and InputKeys.MOD_ALT != 0
        val ctrl = modifiers and InputKeys.MOD_CTRL != 0
        if (key == SpecialKey.TAB && shift) return special(SpecialKey.BACK_TAB, modifiers and InputKeys.MOD_SHIFT.inv())
        val plain = special(key)
        val param = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        if (param == 1) return plain
        val body = plain.decodeToString()
        return when {
            // ESC [ x and ESC O x (the arrows, Home, End, F1-F4): ESC [ 1 ; m x.
            body.length == 3 && body[0] == '\u001B' && body[1] in "[O" && body[2] != 'Z' -> csi("1;$param${body[2]}")
            // ESC [ n ~: ESC [ n ; m ~.
            body.length > 3 && body.startsWith("\u001B[") && body.endsWith("~") ->
                csi("${body.substring(2, body.length - 1)};$param~")
            alt -> byteArrayOf(0x1B) + plain
            else -> plain
        }
    }

    /**
     * [text] typed with the sticky [modifiers] (InputKeys `MOD_*` bits): Shift
     * upper-cases it, Ctrl turns its first character into a control code when
     * it has one (the rest is dropped, as a key press is one character), and
     * Alt prefixes ESC. Win/Super has no encoding and is ignored.
     */
    fun typed(text: String, modifiers: Int): ByteArray {
        val shifted = if (modifiers and InputKeys.MOD_SHIFT != 0) text.uppercase() else text
        val control = if (modifiers and InputKeys.MOD_CTRL != 0) shifted.firstOrNull()?.let(::ctrl) else null
        val body = control ?: text(shifted)
        return if (modifiers and InputKeys.MOD_ALT != 0) byteArrayOf(0x1B) + body else body
    }

    /**
     * Encodes Ctrl + a key. For `A`..`Z`/`a`..`z` this yields the C0 control code
     * (`Ctrl-A` = 0x01 … `Ctrl-Z` = 0x1A); a handful of symbols map to their
     * standard controls (`Ctrl-[` = ESC, `Ctrl-\`, `Ctrl-]`, `Ctrl-Space` = NUL).
     * Returns `null` when there is no control mapping for [char].
     */
    fun ctrl(char: Char): ByteArray? {
        val upper = char.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> byteArrayOf((upper.code and 0x1F).toByte())
            char == ' ' || char == '@' -> byteArrayOf(0)
            char == '[' -> byteArrayOf(0x1B)
            char == '\\' -> byteArrayOf(0x1C)
            char == ']' -> byteArrayOf(0x1D)
            char == '^' -> byteArrayOf(0x1E)
            char == '_' -> byteArrayOf(0x1F)
            else -> null
        }
    }

    /** Encodes Alt/Meta + [text]: ESC prefix followed by the characters. */
    fun alt(text: String): ByteArray = byteArrayOf(0x1B) + text.encodeToByteArray()

    /**
     * Rewrites a cursor key ([SpecialKey.UP]…[SpecialKey.LEFT], HOME, END) from
     * `ESC [ x` to `ESC O x` when the remote asked for application cursor keys
     * (`DECCKM`), as vim, less or tmux do. Anything else passes unchanged.
     */
    fun inCursorMode(bytes: ByteArray, application: Boolean): ByteArray {
        if (!application || bytes.size != 3 || bytes[0] != 0x1B.toByte() || bytes[1] != '['.code.toByte()) return bytes
        return if (bytes[2].toInt().toChar() in "ABCDHF") byteArrayOf(0x1B, 'O'.code.toByte(), bytes[2]) else bytes
    }

    /**
     * Encodes pasted [text]: line breaks become the CR that Enter sends, and the
     * whole text is framed as a bracketed paste when the remote enabled it, so a
     * shell does not run each pasted line as it arrives. The text goes through
     * [pastedText] first.
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val body = pastedText(text).replace("\r\n", "\r").replace('\n', '\r')
        return (if (bracketed) "\u001B[200~$body\u001B[201~" else body).encodeToByteArray()
    }

    /**
     * Pasted [text] reduced to what a paste is meant to carry: printable
     * characters, tabs and line breaks. Every other C0 control, DEL and the C1
     * controls are dropped, so pasted text reaches the remote as plain data.
     */
    fun pastedText(text: String): String = buildString(text.length) {
        for (ch in text) {
            val control = ch < ' ' || ch in '\u007F'..'\u009F'
            if (!control || ch == '\t' || ch == '\n' || ch == '\r') append(ch)
        }
    }

    private fun csi(tail: String): ByteArray = ("[" + tail).encodeToByteArray()

    private fun ss3(tail: String): ByteArray = ("O" + tail).encodeToByteArray()
}
