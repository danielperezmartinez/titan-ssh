package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Constants of the screen frames (mirror of `agent/internal/protocol/screen.go`, ADR-0029). */
object ScreenCodec {
    /** The screen protocol version this client speaks. */
    const val VERSION = 1

    /** [AgentFrame.ScreenHello.quality]: lossless tiles only. */
    const val QUALITY_LOSSLESS = 0

    /** [AgentFrame.ScreenHello.quality]: the agent adapts the JPEG quality to the link. */
    const val QUALITY_AUTO = 255

    /** JPEG quality of the "low" setting: more compression for slow links. */
    const val QUALITY_LOW = 45

    const val ENCODING_FILL = 0
    const val ENCODING_ZLIB = 1
    const val ENCODING_JPEG = 2
}

/** Inflates zlib [data] that must give exactly [size] bytes. */
internal expect fun inflateExactly(data: ByteArray, size: Int): ByteArray

/** Decodes a JPEG of [width]×[height] into opaque ARGB pixels, row after row. */
internal expect fun decodeJpegArgb(data: ByteArray, width: Int, height: Int): IntArray

/**
 * Where the pixels of a remote screen end up to be drawn: a bitmap of the
 * platform (Android `Bitmap`, Skia on the desktop) that [RemoteScreen] updates
 * after each frame. Created and written by the screen's reader; drawn by the UI.
 */
interface ScreenSurface {
    val width: Int
    val height: Int

    /**
     * Copies the [w]×[h] block at ([x], [y]) of [pixels] (opaque ARGB, a whole
     * screen of [width] per row) into the bitmap.
     */
    fun write(pixels: IntArray, x: Int, y: Int, w: Int, h: Int)
}

/** The remote cursor's image, ready to draw with its hot spot at ([hotX], [hotY]). */
class CursorImage(val surface: ScreenSurface, val hotX: Int, val hotY: Int)

/** What a remote desktop tab shows: the screen as of the last whole frame, and the cursor. */
data class ScreenView(
    val surface: ScreenSurface? = null,
    /** Counts the frames shown, so the UI redraws on each one. */
    val frame: Long = 0,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val cursorVisible: Boolean = false,
    val cursor: CursorImage? = null,
)

/**
 * The client's copy of a remote screen (ADR-0029). [apply] takes the screen
 * frames as they arrive: tiles are decoded into a back buffer and reach the
 * [ScreenSurface] only when their frame ends, so the UI never shows half a
 * frame. It returns the sequence number to acknowledge when a frame ends.
 *
 * Driven by one reader; [view] is safe to collect from anywhere. The last
 * image stays after the connection drops, until a new one replaces it.
 */
class RemoteScreen(private val newSurface: (width: Int, height: Int) -> ScreenSurface) {
    private val _view = MutableStateFlow(ScreenView())
    val view: StateFlow<ScreenView> = _view.asStateFlow()

    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)
    private var surface: ScreenSurface? = null

    // The rectangle touched since the last frame end.
    private var dirtyX0 = Int.MAX_VALUE
    private var dirtyY0 = Int.MAX_VALUE
    private var dirtyX1 = 0
    private var dirtyY1 = 0

    /** Applies [frame]; returns the sequence to acknowledge if it ended a frame. */
    fun apply(frame: AgentFrame): Long? {
        when (frame) {
            is AgentFrame.ScreenSize -> resize(frame.width, frame.height)
            is AgentFrame.ScreenTile -> tile(frame)
            is AgentFrame.ScreenFrameEnd -> {
                flush()
                return frame.seq
            }
            is AgentFrame.CursorPos -> _view.value = _view.value.copy(
                cursorX = frame.x,
                cursorY = frame.y,
                cursorVisible = frame.visible,
            )
            is AgentFrame.CursorShape -> cursorShape(frame)
            else -> Unit
        }
        return null
    }

    private fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) {
            throw AgentProtocolException("Screen size out of range: ${w}x$h")
        }
        width = w
        height = h
        pixels = IntArray(w * h) { OPAQUE_BLACK }
        surface = newSurface(w, h)
        dirtyX0 = Int.MAX_VALUE
        dirtyY0 = Int.MAX_VALUE
        dirtyX1 = 0
        dirtyY1 = 0
    }

    private fun tile(t: AgentFrame.ScreenTile) {
        if (t.width <= 0 || t.height <= 0 || t.x + t.width > width || t.y + t.height > height) {
            throw AgentProtocolException("Tile ${t.width}x${t.height} at ${t.x},${t.y} outside ${width}x$height")
        }
        when (t.encoding) {
            ScreenCodec.ENCODING_FILL -> {
                if (t.data.size < 3) throw AgentProtocolException("Short FILL tile")
                val color = rgb(t.data, 0)
                for (y in t.y until t.y + t.height) {
                    pixels.fill(color, y * width + t.x, y * width + t.x + t.width)
                }
            }
            ScreenCodec.ENCODING_ZLIB -> {
                val raw = inflateExactly(t.data, t.width * t.height * 3)
                var i = 0
                for (y in t.y until t.y + t.height) {
                    var p = y * width + t.x
                    repeat(t.width) {
                        pixels[p++] = rgb(raw, i)
                        i += 3
                    }
                }
            }
            ScreenCodec.ENCODING_JPEG -> {
                val argb = decodeJpegArgb(t.data, t.width, t.height)
                for (row in 0 until t.height) {
                    argb.copyInto(pixels, (t.y + row) * width + t.x, row * t.width, (row + 1) * t.width)
                }
            }
            else -> throw AgentProtocolException("Unknown tile encoding ${t.encoding}")
        }
        dirtyX0 = minOf(dirtyX0, t.x)
        dirtyY0 = minOf(dirtyY0, t.y)
        dirtyX1 = maxOf(dirtyX1, t.x + t.width)
        dirtyY1 = maxOf(dirtyY1, t.y + t.height)
    }

    private fun flush() {
        val s = surface ?: return
        if (dirtyX1 > dirtyX0 && dirtyY1 > dirtyY0) {
            s.write(pixels, dirtyX0, dirtyY0, dirtyX1 - dirtyX0, dirtyY1 - dirtyY0)
        }
        dirtyX0 = Int.MAX_VALUE
        dirtyY0 = Int.MAX_VALUE
        dirtyX1 = 0
        dirtyY1 = 0
        _view.value = _view.value.copy(surface = s, frame = _view.value.frame + 1)
    }

    private fun cursorShape(c: AgentFrame.CursorShape) {
        if (c.width <= 0 || c.height <= 0 || c.width > MAX_CURSOR || c.height > MAX_CURSOR) {
            _view.value = _view.value.copy(cursor = null)
            return
        }
        val raw = inflateExactly(c.data, c.width * c.height * 4)
        val argb = IntArray(c.width * c.height) { i ->
            ((raw[i * 4].toInt() and 0xFF) shl 24) or ((raw[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((raw[i * 4 + 2].toInt() and 0xFF) shl 8) or (raw[i * 4 + 3].toInt() and 0xFF)
        }
        val s = newSurface(c.width, c.height)
        s.write(argb, 0, 0, c.width, c.height)
        _view.value = _view.value.copy(cursor = CursorImage(s, c.hotX, c.hotY))
    }

    /** The pixel at ([x], [y]) as of the last applied tile, for tests. */
    internal fun pixelAt(x: Int, y: Int): Int = pixels[y * width + x]

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()

        /** Generous bounds that keep a malformed size from allocating gigabytes. */
        const val MAX_SIDE = 16384
        const val MAX_CURSOR = 256

        fun rgb(b: ByteArray, at: Int): Int =
            OPAQUE_BLACK or ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                (b[at + 2].toInt() and 0xFF)
    }
}

/**
 * How a remote screen of [screenW]×[screenH] fits a view of [viewW]×[viewH]:
 * scaled to fit whole at [zoom] 1, and enlarged by [zoom] around ([focusX],
 * [focusY]) of the screen (the cursor), kept inside the view when it is larger.
 * Pure geometry, shared by the drawing and the pointer mapping.
 */
data class ScreenViewport(
    val viewW: Float,
    val viewH: Float,
    val screenW: Int,
    val screenH: Int,
    val zoom: Float = 1f,
    val focusX: Float = screenW / 2f,
    val focusY: Float = screenH / 2f,
) {
    /** View pixels per screen pixel. */
    val scale: Float =
        if (screenW <= 0 || screenH <= 0 || viewW <= 0f || viewH <= 0f) 0f
        else minOf(viewW / screenW, viewH / screenH) * zoom

    /** Where the screen's top-left corner lands in the view. */
    val offsetX: Float = place(viewW, screenW * scale, focusX * scale)
    val offsetY: Float = place(viewH, screenH * scale, focusY * scale)

    /** The screen pixel under view point ([x], [y]), kept on the screen. */
    fun toScreen(x: Float, y: Float): Pair<Int, Int> {
        if (scale <= 0f) return 0 to 0
        val sx = ((x - offsetX) / scale).toInt().coerceIn(0, screenW - 1)
        val sy = ((y - offsetY) / scale).toInt().coerceIn(0, screenH - 1)
        return sx to sy
    }

    private fun place(view: Float, content: Float, focus: Float): Float =
        if (content <= view) (view - content) / 2f
        else (view / 2f - focus).coerceIn(view - content, 0f)
}
