package io.github.danielperezmartinez.titanssh.rdp

import androidx.compose.ui.graphics.ImageBitmap

/**
 * What the RDP view draws: the desktop's pixels ([RdpScreen.Frame]) as an
 * [ImageBitmap], one per desktop size. [update] runs on the UI thread with the
 * whole frame; the view reads [image] after it.
 */
expect class RdpFrameImage(width: Int, height: Int) {
    val image: ImageBitmap

    fun update(pixels: ByteArray)
}
