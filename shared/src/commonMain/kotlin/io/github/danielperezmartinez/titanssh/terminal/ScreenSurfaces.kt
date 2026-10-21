package io.github.danielperezmartinez.titanssh.terminal

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A platform bitmap for a remote screen or its cursor (ADR-0029). Writes come
 * from the screen's reader; [imageBitmap] gives the UI the last complete
 * image, never one being written.
 */
expect fun createScreenSurface(width: Int, height: Int): ScreenSurface

/** The image to draw for a surface made by [createScreenSurface]. */
expect fun ScreenSurface.imageBitmap(): ImageBitmap?
