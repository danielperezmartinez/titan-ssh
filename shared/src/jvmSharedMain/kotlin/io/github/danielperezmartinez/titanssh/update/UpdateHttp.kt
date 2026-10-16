package io.github.danielperezmartinez.titanssh.update

import io.github.danielperezmartinez.titanssh.BuildInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two requests the updater makes; a fake replaces it in the tests. */
interface UpdateHttp {
    /** The body of [url], refusing more than [maxBytes]. */
    suspend fun get(url: String, maxBytes: Long): ByteArray

    /**
     * Streams [url] into [dest], refusing more than [maxBytes], and returns the
     * SHA-256 (hex) of what it wrote. [onProgress] gets the bytes written so far.
     */
    suspend fun download(url: String, dest: File, maxBytes: Long, onProgress: (Long) -> Unit): String
}

/**
 * [UpdateHttp] over `HttpURLConnection`, like the agent downloads: no account
 * and no user data, only the app version in the `User-Agent`. GitHub answers
 * asset requests with an https redirect, which it follows by itself.
 */
class UrlConnectionHttp : UpdateHttp {
    override suspend fun get(url: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        open(url).useConnection { connection ->
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw IOException("response larger than $maxBytes bytes")
                }
                out.toByteArray()
            }
        }
    }

    override suspend fun download(url: String, dest: File, maxBytes: Long, onProgress: (Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            open(url).useConnection { connection ->
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                connection.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) throw IOException("download larger than $maxBytes bytes")
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            onProgress(written)
                        }
                    }
                }
                UpdateFiles.hex(digest.digest())
            }
        }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://") || isLocalTestOrigin(url)) { "not an https URL: $url" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "titan-ssh/${BuildInfo.VERSION}")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        return connection
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode")
            block(this)
        } finally {
            disconnect()
        }

    /**
     * Plain http only to the loopback, and only when the build was pointed at a
     * local origin for an end-to-end test (-PtitanUpdateApi). The signature
     * still decides what gets installed.
     */
    private fun isLocalTestOrigin(url: String): Boolean =
        !BuildInfo.UPDATE_API.startsWith("https://") &&
            (url.startsWith("http://127.0.0.1:") || url.startsWith("http://10.0.2.2:"))
}
