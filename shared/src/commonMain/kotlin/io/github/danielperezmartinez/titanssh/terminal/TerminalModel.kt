package io.github.danielperezmartinez.titanssh.terminal

/**
 * Terminal screen model shared by the emulator and the UI. Kept free of Compose
 * types so the emulator and its parsing can be unit-tested headless; the UI maps
 * [TermColor] to concrete Compose colors through [AnsiPalette].
 */

/** A cell colour: the default fg/bg, one of the 256 indexed colours, or a true-colour RGB. */
sealed interface TermColor {
    /** Inherit the terminal's default foreground/background. */
    data object Default : TermColor

    /** An xterm palette index in `0..255` (0..15 are the ANSI base colours). */
    data class Indexed(val index: Int) : TermColor

    /** A 24-bit true colour. */
    data class Rgb(val r: Int, val g: Int, val b: Int) : TermColor
}

/**
 * The rendition (SGR state) a cell was written with. The emulator shares one
 * instance across every cell written between two SGR changes, so the renderer
 * can group a row into runs by comparing styles.
 */
data class CellStyle(
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val inverse: Boolean = false,
    val invisible: Boolean = false,
) {
    companion object {
        val Default = CellStyle()
    }
}

/**
 * One character cell of the screen, with its rendition. A wide glyph (CJK,
 * emoji) takes two cells: the first has [width] 2 and the second is a spacer
 * with [width] 0 that carries the same style and draws nothing.
 */
data class TerminalCell(
    val codePoint: Int = ' '.code,
    val style: CellStyle = CellStyle.Default,
    val width: Int = 1,
) {
    /** The cell's character; a code point beyond the BMP reads as U+FFFD here (see [text]). */
    val char: Char get() = if (codePoint in 0..0xFFFF) codePoint.toChar() else '�'

    /** The cell's character as a string, surrogate pair included. */
    val text: String
        get() = if (codePoint <= 0xFFFF) {
            codePoint.toChar().toString()
        } else {
            val v = codePoint - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }

    val fg: TermColor get() = style.fg
    val bg: TermColor get() = style.bg
    val bold: Boolean get() = style.bold
    val inverse: Boolean get() = style.inverse

    /** A plain space with the default background: what trailing padding is made of. */
    val isBlank: Boolean
        get() = codePoint == ' '.code && width == 1 && style.bg == TermColor.Default && !style.inverse

    companion object {
        val Blank = TerminalCell()
    }
}

/**
 * One row of the grid. [wrapped] is true when the text continues on the next
 * row because it reached the right margin (a soft wrap, not a newline), which
 * lets a resize re-join and re-wrap the line to the new width.
 *
 * Rows are immutable and compared by identity: the emulator hands out the same
 * instance until the row changes, so the UI can cache what it drew for it.
 */
class TerminalRow(private val cells: List<TerminalCell>, val wrapped: Boolean = false) : List<TerminalCell> by cells

/** A row of cells. */
typealias TerminalLine = TerminalRow

/**
 * An immutable snapshot of the terminal at a point in time, for the UI to render.
 * [scrollback] holds lines that have scrolled off the top; [screen] is the live
 * grid (always [rows] lines of [columns] cells).
 */
data class TerminalSnapshot(
    val scrollback: List<TerminalLine>,
    val screen: List<TerminalLine>,
    val columns: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
    /** False while the remote hides the cursor (`DECTCEM`, `CSI ?25l`). */
    val cursorVisible: Boolean = true,
    /** True while a full-screen app runs on the alternate buffer. */
    val altScreen: Boolean = false,
    /**
     * Screen rows in use from the top: up to the last row with content or the
     * cursor. A resize keeps these and drops the blank rows below them.
     */
    val contentRows: Int = 0,
    /** Arrow keys must send `ESC O x` instead of `ESC [ x` (`DECCKM`, `CSI ?1h`). */
    val applicationCursorKeys: Boolean = false,
    /** Pasted text must be wrapped in `ESC [200~` … `ESC [201~` (`CSI ?2004h`). */
    val bracketedPaste: Boolean = false,
) {
    companion object {
        val Empty = TerminalSnapshot(emptyList(), emptyList(), 0, 0, 0, 0)
    }
}
