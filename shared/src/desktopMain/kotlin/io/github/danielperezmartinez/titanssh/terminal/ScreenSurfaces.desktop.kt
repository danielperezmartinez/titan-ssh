package io.github.danielperezmartinez.titanssh.terminal

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/**
 * The screen in immutable Skia images of [TILE]×[TILE]: a frame only remakes,
 * and the GPU only uploads again, the images under its changed rectangle, not
 * the whole screen. [write] (the reader) turns the rectangle into BGRA bytes
 * in [published]; the UI thread remakes the images from it when it draws, so
 * an image being drawn never changes.
 */
private class SkiaScreenSurface(override val width: Int, override val height: Int) : ScreenSurface {
    private val lock = Any()
    private val published = ByteArray(width * height * 4)
    private var first = true

    // The rectangle written since the images last caught up.
    private var x0 = Int.MAX_VALUE
    private var y0 = Int.MAX_VALUE
    private var x1 = 0
    private var y1 = 0

    private val cols = (width + TILE - 1) / TILE
    private val rows = (height + TILE - 1) / TILE
    private val tiles = arrayOfNulls<Image>(cols * rows)
    private val tileBytes = ByteArray(TILE * TILE * 4)
    // Without antialiasing each view pixel belongs to one image: no seams.
    private val paint = Paint().apply { isAntiAlias = false }

    override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            // The first write takes the whole screen, as there are no images yet.
            val ax = if (first) 0 else x
            val ay = if (first) 0 else y
            val aw = if (first) width else w
            val ah = if (first) height else h
            first = false
            for (row in ay until ay + ah) {
                var p = row * width + ax
                var b = p * 4
                repeat(aw) {
                    val c = pixels[p++]
                    published[b] = c.toByte()
                    published[b + 1] = (c shr 8).toByte()
                    published[b + 2] = (c shr 16).toByte()
                    published[b + 3] = (c ushr 24).toByte()
                    b += 4
                }
            }
            x0 = minOf(x0, ax)
            y0 = minOf(y0, ay)
            x1 = maxOf(x1, ax + aw)
            y1 = maxOf(y1, ay + ah)
        }
    }

    /** Remakes the images under the written rectangle. On the UI thread. */
    private fun catchUp() {
        synchronized(lock) {
            if (x1 <= x0 || y1 <= y0) return
            for (row in y0 / TILE..(y1 - 1) / TILE) {
                for (col in x0 / TILE..(x1 - 1) / TILE) {
                    val tx = col * TILE
                    val ty = row * TILE
                    val tw = minOf(TILE, width - tx)
                    val th = minOf(TILE, height - ty)
                    for (r in 0 until th) {
                        published.copyInto(tileBytes, r * tw * 4, ((ty + r) * width + tx) * 4, ((ty + r) * width + tx + tw) * 4)
                    }
                    val i = row * cols + col
                    tiles[i]?.close()
                    tiles[i] = Image.makeRaster(ImageInfo(tw, th, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), tileBytes, tw * 4)
                }
            }
            x0 = Int.MAX_VALUE
            y0 = Int.MAX_VALUE
            x1 = 0
            y1 = 0
        }
    }

    fun draw(canvas: Canvas, left: Float, top: Float, scale: Float, alpha: Float, sampling: SamplingMode) {
        catchUp()
        paint.setAlphaf(alpha)
        canvas.save()
        // On whole pixels, as Compose's drawImage: crisp at one to one.
        canvas.translate(left.roundToInt().toFloat(), top.roundToInt().toFloat())
        canvas.scale(scale, scale)
        for (i in tiles.indices) {
            val image = tiles[i] ?: continue
            val tx = (i % cols * TILE).toFloat()
            val ty = (i / cols * TILE).toFloat()
            canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeXYWH(tx, ty, image.width.toFloat(), image.height.toFloat()),
                sampling,
                paint,
                false,
            )
        }
        canvas.restore()
    }

    private companion object {
        const val TILE = 256
    }
}

actual fun createScreenSurface(width: Int, height: Int): ScreenSurface = SkiaScreenSurface(width, height)

actual fun DrawScope.drawScreenSurface(
    surface: ScreenSurface,
    left: Float,
    top: Float,
    scale: Float,
    alpha: Float,
    filterQuality: FilterQuality,
) {
    val s = surface as? SkiaScreenSurface ?: return
    // The same sampling as Compose's own drawImage for each quality.
    val sampling = when (filterQuality) {
        FilterQuality.None -> FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
        FilterQuality.Low -> FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)
        FilterQuality.Medium -> FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST)
        else -> SamplingMode.MITCHELL
    }
    drawIntoCanvas { s.draw(it.nativeCanvas, left, top, scale, alpha, sampling) }
}
