package im.gar.titanssh.terminal

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

    private fun csi(tail: String): ByteArray = ("[" + tail).encodeToByteArray()
}

/**
 * One button on the Android accessory key bar (the soft keyboard lacks Esc, Tab,
 * Ctrl, Alt, arrows and shell symbols). A [Modifier] key is sticky (applied to
 * the next key press); the rest send their [bytes] immediately.
 */
sealed interface AccessoryKey {
    val label: String

    /** A sticky modifier toggled on/off, applied to the next key. */
    data class Modifier(override val label: String, val kind: ModifierKind) : AccessoryKey

    /** A key that emits a fixed byte sequence when pressed. */
    data class Send(override val label: String, val bytes: ByteArray) : AccessoryKey {
        override fun equals(other: Any?): Boolean =
            other is Send && other.label == label && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * label.hashCode() + bytes.contentHashCode()
    }
}

/** The two sticky modifiers the accessory bar offers. */
enum class ModifierKind { CTRL, ALT }

/**
 * The default Android accessory bar layout: the keys the task calls out (Esc,
 * Tab, Ctrl, Alt, the four arrows) plus the shell symbols `| / - ~`.
 */
val DefaultAccessoryKeys: List<AccessoryKey> = listOf(
    AccessoryKey.Send("Esc", TerminalKeys.special(TerminalKeys.SpecialKey.ESCAPE)),
    AccessoryKey.Send("Tab", TerminalKeys.special(TerminalKeys.SpecialKey.TAB)),
    AccessoryKey.Modifier("Ctrl", ModifierKind.CTRL),
    AccessoryKey.Modifier("Alt", ModifierKind.ALT),
    AccessoryKey.Send("<", TerminalKeys.special(TerminalKeys.SpecialKey.LEFT)),
    AccessoryKey.Send("v", TerminalKeys.special(TerminalKeys.SpecialKey.DOWN)),
    AccessoryKey.Send("^", TerminalKeys.special(TerminalKeys.SpecialKey.UP)),
    AccessoryKey.Send(">", TerminalKeys.special(TerminalKeys.SpecialKey.RIGHT)),
    AccessoryKey.Send("|", TerminalKeys.text("|")),
    AccessoryKey.Send("/", TerminalKeys.text("/")),
    AccessoryKey.Send("-", TerminalKeys.text("-")),
    AccessoryKey.Send("~", TerminalKeys.text("~")),
)
