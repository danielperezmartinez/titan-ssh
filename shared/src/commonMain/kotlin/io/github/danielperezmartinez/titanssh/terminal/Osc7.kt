package io.github.danielperezmartinez.titanssh.terminal

/**
 * The OSC 7 sequence with which a shell announces its directory at each
 * prompt: `ESC ] 7 ; file://host/path ST`, the path percent-encoded. Shells
 * send it only when configured to (fish does; bash and zsh with `vte.sh`).
 */
object Osc7 {

    /**
     * The path of an OSC 7 payload ([uri], after `7;`), or null when it is not
     * a `file:` URI with an absolute path. A Windows drive path
     * (`file:///C:/Users`) keeps its leading slash, the form SFTP uses.
     */
    fun path(uri: String): String? {
        if (!uri.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = uri.substring(SCHEME.length)
        // The host part is skipped: what matters is the path on the destination.
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val path = percentDecode(rest.substring(slash)) ?: return null
        return path.takeIf { it.isNotEmpty() && '\u0000' !in it }
    }

    /** [s] with its `%XX` escapes decoded as UTF-8; null when an escape is malformed. */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val bytes = s.encodeToByteArray()
        val out = ByteArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte()) {
                if (i + 2 >= bytes.size) return null
                val hex = bytes.decodeToString(i + 1, i + 3)
                out[n++] = (hex.toIntOrNull(16) ?: return null).toByte()
                i += 3
            } else {
                out[n++] = bytes[i++]
            }
        }
        return out.decodeToString(0, n)
    }

    private const val SCHEME = "file://"
}
