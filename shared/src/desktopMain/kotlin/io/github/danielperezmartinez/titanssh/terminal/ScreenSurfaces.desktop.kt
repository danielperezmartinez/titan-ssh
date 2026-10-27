package io.github.danielperezmartinez.titanssh.terminal

import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Keeps the pixels as BGRA bytes and publishes an immutable Skia image after
 * each write: the UI draws one snapshot while the next is being made.
 */
private class SkiaScreenSurface(override val width: Int, override val height: Int) : ScreenSurface {
    private val bytes = ByteArray(width * height * 4)
    private val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)

    @Volatile
    var image: ImageBitmap? = null
        private set

    override fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        for (row in y until y + h) {
            var p = row * width + x
            var b = p * 4
            repeat(w) {
                val c = pixels[p++]
                bytes[b] = c.toByte()
                bytes[b + 1] = (c shr 8).toByte()
                bytes[b + 2] = (c shr 16).toByte()
                bytes[b + 3] = (c ushr 24).toByte()
                b += 4
            }
        }
        image = Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
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
    val image = (surface as? SkiaScreenSurface)?.image ?: return
    drawImage(
        image,
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt()),
        alpha = alpha,
        filterQuality = filterQuality,
    )
}
