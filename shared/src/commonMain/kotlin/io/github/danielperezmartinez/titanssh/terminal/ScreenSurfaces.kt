package io.github.danielperezmartinez.titanssh.terminal

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A platform bitmap for a remote screen or its cursor (ADR-0029). Writes come
 * from the screen's reader; [drawScreenSurface] draws the last complete
 * image, never one being written.
 */
expect fun createScreenSurface(width: Int, height: Int): ScreenSurface

/**
 * Draws a surface made by [createScreenSurface] with its top-left corner at
 * ([left], [top]) and [scale] view pixels per surface pixel.
 */
expect fun DrawScope.drawScreenSurface(
    surface: ScreenSurface,
    left: Float,
    top: Float,
    scale: Float,
    alpha: Float = 1f,
    filterQuality: FilterQuality = FilterQuality.Low,
)
