package io.github.danielperezmartinez.titanssh.rdp

/**
 * The JNI functions of the native `titan_rdp` library (`rdp/src/lib.rs`,
 * ADR-0028). Only Kotlin calls into Rust; sessions are numbered handles, and a
 * stale one is a no-op. Load the library before the first call
 * ([loadRdpEngine]).
 */
internal object RdpNative {
    external fun start(
        username: String,
        password: ByteArray,
        domain: String?,
        serverName: String,
        width: Int,
        height: Int,
        allowTlsLogin: Boolean,
        clientName: String,
    ): Long

    external fun feed(handle: Long, data: ByteArray, length: Int): Boolean

    external fun feedEof(handle: Long)

    external fun drain(handle: Long, buffer: ByteArray): Int

    external fun nextEvent(handle: Long, timeoutMillis: Int): String?

    external fun answerCertificate(handle: Long, accept: Boolean)

    external fun input(handle: Long, kind: Int, a: Int, b: Int)

    external fun copyFrame(handle: Long, dst: ByteArray, width: Int, height: Int, bgra: Boolean): Long

    external fun frameSize(handle: Long): Int

    external fun free(handle: Long)
}

/** [RdpEngine] over [RdpNative]; [bgra] is the order the platform's bitmaps take. */
internal class JniRdpEngine(override val bgra: Boolean) : RdpEngine {
    override fun start(params: RdpConnectParams): RdpNativeSession {
        val handle = RdpNative.start(
            params.username,
            params.password,
            params.domain,
            params.serverName,
            params.width,
            params.height,
            params.allowTlsWithoutNla,
            params.clientName,
        )
        check(handle != 0L) { "the RDP engine could not start" }
        return JniRdpSession(handle, bgra)
    }
}

private class JniRdpSession(private val handle: Long, private val bgra: Boolean) : RdpNativeSession {
    override fun feed(data: ByteArray, length: Int): Boolean = RdpNative.feed(handle, data, length)
    override fun feedEof() = RdpNative.feedEof(handle)
    override fun drain(buffer: ByteArray): Int = RdpNative.drain(handle, buffer)
    override fun nextEvent(timeoutMillis: Int): String? = RdpNative.nextEvent(handle, timeoutMillis)
    override fun answerCertificate(accept: Boolean) = RdpNative.answerCertificate(handle, accept)
    override fun input(kind: Int, a: Int, b: Int) = RdpNative.input(handle, kind, a, b)
    override fun copyFrame(dst: ByteArray, width: Int, height: Int): Long =
        RdpNative.copyFrame(handle, dst, width, height, bgra)
    override fun free() = RdpNative.free(handle)
}

/** Loads the library once; the result is kept for the process. */
internal fun loadRdpLibraryOnce(load: () -> Unit, bgra: Boolean): RdpAvailability = synchronized(RdpNative) {
    loaded ?: runCatching {
        load()
        RdpAvailability.Available(JniRdpEngine(bgra))
    }.getOrElse { e ->
        RdpAvailability.Missing(e.message ?: e::class.simpleName.orEmpty())
    }.also { loaded = it }
}

private var loaded: RdpAvailability? = null
