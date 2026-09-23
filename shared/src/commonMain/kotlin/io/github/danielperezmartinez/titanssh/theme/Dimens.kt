package io.github.danielperezmartinez.titanssh.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing, radius and touch tokens from the "Tokens visuales dark-first (base
 * opencode)" visual decision. Base 8px with fine steps; radii 4px interactive /
 * 0px containers; 44px minimum touch target on Android.
 */
object TitanDimens {
    val SpaceXxs = 1.dp
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp
    val SpaceSection = 48.dp

    val RadiusNone = 0.dp
    val RadiusSm = 4.dp

    val Hairline = 1.dp

    /** Minimum touch target (Android ergonomics from the visual decision). */
    val TouchTarget = 44.dp
}
