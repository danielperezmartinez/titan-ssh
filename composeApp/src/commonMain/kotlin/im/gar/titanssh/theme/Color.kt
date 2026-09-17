package im.gar.titanssh.theme

import androidx.compose.ui.graphics.Color

/**
 * titan-ssh dark-first color tokens.
 *
 * Source of truth: the "Tokens visuales dark-first (base opencode)" visual
 * decision in the project vault. Keep this file in sync with that note; do not
 * introduce colors that are not part of the agreed token set.
 */
object TitanColors {
    // Surfaces (from base to elevated)
    val Canvas = Color(0xFF201D1D)
    val Surface = Color(0xFF302C2C)
    val SurfaceElevated = Color(0xFF3A3636)
    val TerminalBg = Color(0xFF161313)

    // Text (over dark)
    val Ink = Color(0xFFFDFCFC)
    val Body = Color(0xFFD8D5D5)
    val Mute = Color(0xFF9A9898)
    val Stone = Color(0xFF6E6E73)

    // Lines
    val HairlineStrong = Color(0xFF646262)

    // Semantic (Apple dark-mode variants) — reserved for states, not decoration
    val Accent = Color(0xFF0A84FF)
    val Success = Color(0xFF30D158)
    val Warning = Color(0xFFFF9F0A)
    val Danger = Color(0xFFFF453A)
}
