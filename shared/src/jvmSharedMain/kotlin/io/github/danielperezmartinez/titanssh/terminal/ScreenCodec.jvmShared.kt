package io.github.danielperezmartinez.titanssh.terminal

import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual fun inflateExactly(data: ByteArray, size: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArray(size)
        var n = 0
        while (n < size) {
            val got = inflater.inflate(out, n, size - n)
            if (got == 0) {
                if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
            }
            n += got
        }
        if (n != size) throw AgentProtocolException("zlib tile gave $n of $size bytes")
        return out
    } catch (e: DataFormatException) {
        throw AgentProtocolException("Corrupt zlib tile: ${e.message}")
    } finally {
        inflater.end()
    }
}
