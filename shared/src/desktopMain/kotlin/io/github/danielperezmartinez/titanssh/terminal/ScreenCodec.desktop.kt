package io.github.danielperezmartinez.titanssh.terminal

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

internal actual fun decodeJpegArgb(data: ByteArray, width: Int, height: Int): IntArray {
    val img = runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull()
        ?: throw AgentProtocolException("Undecodable JPEG tile")
    if (img.width != width || img.height != height) {
        throw AgentProtocolException("JPEG tile is ${img.width}x${img.height}, expected ${width}x$height")
    }
    return img.getRGB(0, 0, width, height, null, 0, width)
}
