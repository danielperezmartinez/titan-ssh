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

/** One character cell of the screen, with its rendition. */
data class TerminalCell(
    val char: Char = ' ',
    val fg: TermColor = TermColor.Default,
    val bg: TermColor = TermColor.Default,
    val bold: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        val Blank = TerminalCell()
    }
}

/** A row of cells. */
typealias TerminalLine = List<TerminalCell>

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
) {
    companion object {
        val Empty = TerminalSnapshot(emptyList(), emptyList(), 0, 0, 0, 0)
    }
}
