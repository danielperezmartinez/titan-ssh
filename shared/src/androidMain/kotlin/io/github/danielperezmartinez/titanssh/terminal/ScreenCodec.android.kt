package io.github.danielperezmartinez.titanssh.terminal

import android.graphics.BitmapFactory

internal actual fun decodeJpegArgb(data: ByteArray, width: Int, height: Int): IntArray {
    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
        ?: throw AgentProtocolException("Undecodable JPEG tile")
    try {
        if (bmp.width != width || bmp.height != height) {
            throw AgentProtocolException("JPEG tile is ${bmp.width}x${bmp.height}, expected ${width}x$height")
        }
        val out = IntArray(width * height)
        bmp.getPixels(out, 0, width, 0, 0, width, height)
        return out
    } finally {
        bmp.recycle()
    }
}
