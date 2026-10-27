package io.github.danielperezmartinez.titanssh.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/**
 * The screen in bitmaps of [TILE]×[TILE]: a frame only changes, and the GPU
 * only uploads again, the bitmaps under its changed rectangle, not the whole
 * screen. [write] (the reader) copies the rectangle into [published]; the UI
 * thread brings the bitmaps up to date from it when it draws, so a bitmap is
 * never written while it is being drawn.
 */
private class AndroidScreenSurface(override val width: Int, override val height: Int) : ScreenSurface {
    private val lock = Any()
    private val published = IntArray(width * height)
    private var first = true

    // The rectangle written since the bitmaps last caught up.
    private var x0 = Int.MAX_VALUE
    private var y0 = Int.MAX_VALUE
    private var x1 = 0
    private var y1 = 0

    private val cols = (width + TILE - 1) / TILE
    private val rows = (height + TILE - 1) / TILE
    private val tiles = Array(cols * rows) { i ->
        val tx = i % cols * TILE
        val ty = i / cols * TILE
        Bitmap.createBitmap(minOf(TILE, width - tx), minOf(TILE, height - ty), Bitmap.Config.ARGB_8888)
    }
    private val paint = Paint()

    override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            // The first write takes the whole screen, as the bitmaps start empty.
            val ax = if (first) 0 else x
            val ay = if (first) 0 else y
            val aw = if (first) width else w
            val ah = if (first) height else h
            first = false
            for (row in ay until ay + ah) pixels.copyInto(published, row * width + ax, row * width + ax, row * width + ax + aw)
            x0 = minOf(x0, ax)
            y0 = minOf(y0, ay)
            x1 = maxOf(x1, ax + aw)
            y1 = maxOf(y1, ay + ah)
        }
    }

    /** Brings the bitmaps under the written rectangle up to date. On the UI thread. */
    private fun catchUp() {
        synchronized(lock) {
            if (x1 <= x0 || y1 <= y0) return
            for (row in y0 / TILE..(y1 - 1) / TILE) {
                for (col in x0 / TILE..(x1 - 1) / TILE) {
                    val tx = col * TILE
                    val ty = row * TILE
                    val bmp = tiles[row * cols + col]
                    val cx0 = maxOf(x0, tx)
                    val cy0 = maxOf(y0, ty)
                    val cx1 = minOf(x1, tx + bmp.width)
                    val cy1 = minOf(y1, ty + bmp.height)
                    bmp.setPixels(published, cy0 * width + cx0, width, cx0 - tx, cy0 - ty, cx1 - cx0, cy1 - cy0)
                }
            }
            x0 = Int.MAX_VALUE
            y0 = Int.MAX_VALUE
            x1 = 0
            y1 = 0
        }
    }

    fun draw(canvas: Canvas, left: Float, top: Float, scale: Float, alpha: Float, filter: Boolean) {
        catchUp()
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        paint.isFilterBitmap = filter
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        for (i in tiles.indices) {
            canvas.drawBitmap(tiles[i], (i % cols * TILE).toFloat(), (i / cols * TILE).toFloat(), paint)
        }
        canvas.restore()
    }

    private companion object {
        const val TILE = 256
    }
}

actual fun createScreenSurface(width: Int, height: Int): ScreenSurface = AndroidScreenSurface(width, height)

actual fun DrawScope.drawScreenSurface(
    surface: ScreenSurface,
    left: Float,
    top: Float,
    scale: Float,
    alpha: Float,
    filterQuality: FilterQuality,
) {
    val s = surface as? AndroidScreenSurface ?: return
    drawIntoCanvas { s.draw(it.nativeCanvas, left, top, scale, alpha, filterQuality != FilterQuality.None) }
}
