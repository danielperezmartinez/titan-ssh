package io.github.danielperezmartinez.titanssh.terminal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Two bitmaps in turn: a write goes to the one the UI is not drawing, then it
 * becomes the one to draw. Since [write] gets the whole screen's pixels, the
 * back bitmap catches up with the previous write's rectangle too.
 */
private class AndroidScreenSurface(override val width: Int, override val height: Int) : ScreenSurface {
    private val bitmaps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val images = bitmaps.map { it.asImageBitmap() }
    private var front = 0
    private var last: IntArray? = null // x, y, w, h of the previous write
    private val written = BooleanArray(2)

    @Volatile
    var image: ImageBitmap? = null
        private set

    override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        val back = 1 - front
        val prev = last
        var x0 = x
        var y0 = y
        var x1 = x + w
        var y1 = y + h
        if (prev != null && written[back]) {
            x0 = minOf(x0, prev[0]); y0 = minOf(y0, prev[1])
            x1 = maxOf(x1, prev[0] + prev[2]); y1 = maxOf(y1, prev[1] + prev[3])
        } else {
            // This bitmap has never been written: it takes the whole screen.
            x0 = 0; y0 = 0; x1 = width; y1 = height
        }
        bitmaps[back].setPixels(pixels, y0 * width + x0, width, x0, y0, x1 - x0, y1 - y0)
        written[back] = true
        last = intArrayOf(x, y, w, h)
        front = back
        image = images[back]
    }
}

actual fun createScreenSurface(width: Int, height: Int): ScreenSurface = AndroidScreenSurface(width, height)

actual fun ScreenSurface.imageBitmap(): ImageBitmap? = (this as? AndroidScreenSurface)?.image
