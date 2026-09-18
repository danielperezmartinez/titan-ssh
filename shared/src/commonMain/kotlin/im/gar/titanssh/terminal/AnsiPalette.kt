package im.gar.titanssh.terminal

/**
 * The terminal colour palette, defined by the visual decision
 * [[Tokens visuales dark-first base opencode]] for indices 0..15 and completed
 * with the standard xterm 6x6x6 cube (16..231) and greyscale ramp (232..255).
 *
 * Colours are returned as packed `0xRRGGBB` ints so this stays Compose-free and
 * testable; the UI adds the alpha and wraps them in a Compose `Color`.
 */
object AnsiPalette {

    /** Default foreground (`#d8d5d5`). */
    const val DEFAULT_FG: Int = 0xD8D5D5

    /** Default background, the terminal well (`#161313`). */
    const val DEFAULT_BG: Int = 0x161313

    /** Cursor colour (`accent`). */
    const val CURSOR: Int = 0x0A84FF

    // ANSI 0..15: the agreed dark-first palette (normal 0..7, bright 8..15).
    private val BASE16 = intArrayOf(
        0x201D1D, 0xFF453A, 0x30D158, 0xFF9F0A, 0x0A84FF, 0xBF5AF2, 0x5AC8FA, 0xD8D5D5,
        0x6E6E73, 0xFF6961, 0x66D97E, 0xFFB340, 0x409CFF, 0xDA8FFF, 0x8FE0FF, 0xFDFCFC,
    )

    /** Resolves an xterm palette [index] (`0..255`) to a packed `0xRRGGBB` int. */
    fun rgb(index: Int): Int = when {
        index in 0..15 -> BASE16[index]
        index in 16..231 -> cube(index - 16)
        index in 232..255 -> grey(index - 232)
        else -> DEFAULT_FG
    }

    private fun cube(n: Int): Int {
        val r = component((n / 36) % 6)
        val g = component((n / 6) % 6)
        val b = component(n % 6)
        return (r shl 16) or (g shl 8) or b
    }

    /** xterm cube step: 0 -> 0, else 55 + 40*v. */
    private fun component(v: Int): Int = if (v == 0) 0 else 55 + 40 * v

    private fun grey(n: Int): Int {
        val v = 8 + 10 * n
        return (v shl 16) or (v shl 8) or v
    }

    /** Resolves a [TermColor] to a packed `0xRRGGBB` int, given the defaults. */
    fun resolve(color: TermColor, default: Int): Int = when (color) {
        TermColor.Default -> default
        is TermColor.Indexed -> rgb(color.index)
        is TermColor.Rgb -> (color.r shl 16) or (color.g shl 8) or color.b
    }
}
