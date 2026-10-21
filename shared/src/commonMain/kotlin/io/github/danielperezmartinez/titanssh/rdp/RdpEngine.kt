package io.github.danielperezmartinez.titanssh.rdp

/**
 * The embedded RDP client (ADR-0028): IronRDP in the native `titan_rdp`
 * library. It never opens a socket; [RdpScreen] moves the bytes between it and
 * an SSH `direct-tcpip` channel. Obtain it with [loadRdpEngine].
 */
interface RdpEngine {
    /** Byte order [RdpNativeSession.copyFrame] writes: B, G, R, A when true, else R, G, B, A. */
    val bgra: Boolean

    /** Starts connecting; the caller wipes [RdpConnectParams.password] afterwards. */
    fun start(params: RdpConnectParams): RdpNativeSession
}

/** The platform's [RdpEngine], or why this build or device has none. */
sealed interface RdpAvailability {
    class Available(val engine: RdpEngine) : RdpAvailability

    /** The native library is not in this build or could not load ([detail]). */
    data class Missing(val detail: String) : RdpAvailability
}

/** What [RdpEngine.start] needs. */
class RdpConnectParams(
    val username: String,
    /** UTF-8 bytes; wiped by the caller once the engine has them. */
    val password: ByteArray,
    val domain: String?,
    /** The name CredSSP targets: the RDP server as the destination knows it. */
    val serverName: String,
    val width: Int,
    val height: Int,
    val allowTlsWithoutNla: Boolean,
    val clientName: String,
)

/**
 * One connection of the native engine, mirroring `RdpNative`. Every call but
 * [input] and [answerCertificate] may block; [RdpScreen] gives each direction
 * a thread of its own.
 */
interface RdpNativeSession {
    /** Hands the engine [length] bytes from the server; false once it no longer reads. */
    fun feed(data: ByteArray, length: Int): Boolean

    /** The server side closed: the engine reads an end of stream. */
    fun feedEof()

    /** Waits for bytes for the server; how many it put in [buffer], or -1 at the end. */
    fun drain(buffer: ByteArray): Int

    /** The next event as tab-separated text ([RdpEvent.parse]), or null after [timeoutMillis]. */
    fun nextEvent(timeoutMillis: Int): String?

    fun answerCertificate(accept: Boolean)

    /** One input of [kind] ([RdpInput]); [a] and [b] depend on it. */
    fun input(kind: Int, a: Int, b: Int)

    /**
     * Copies what changed into [dst], a whole frame of [width] × [height]
     * pixels: the rectangle packed as `x | y << 16 | w << 32 | h << 48`, 0 if
     * nothing changed, -1 if the desktop has another size.
     */
    fun copyFrame(dst: ByteArray, width: Int, height: Int): Long

    /** Stops the engine; blocked calls return. Safe to call twice. */
    fun free()
}

/** Input kinds of [RdpNativeSession.input], as the native library numbers them. */
object RdpInput {
    /** a = x, b = y, in desktop pixels. */
    const val MOUSE_MOVE = 0

    /** a = button (0 left, 1 middle, 2 right), b = 1 down / 0 up. */
    const val MOUSE_BUTTON = 1

    /** a = 1 vertical / 0 horizontal, b = units (120 per notch, positive up or right). */
    const val WHEEL = 2

    /** a = PC/AT set-1 scancode (`0xE0xx` extended), b = 1 down / 0 up. */
    const val KEY = 3

    /** a = Unicode code point, b = 1 down / 0 up. */
    const val UNICODE = 4

    const val RELEASE_ALL = 5

    /** a = width, b = height. */
    const val RESIZE = 6

    const val CLOSE = 7
}

/** An event of [RdpNativeSession.nextEvent]. */
sealed interface RdpEvent {
    data class Certificate(val sha256: String, val subject: String) : RdpEvent
    data class Desktop(val width: Int, val height: Int) : RdpEvent
    data object Frame : RdpEvent
    data class Ended(val kind: String, val message: String) : RdpEvent

    companion object {
        /** Parses the native text form; null for anything unknown. */
        fun parse(text: String): RdpEvent? {
            val parts = text.split('\t')
            return when (parts[0]) {
                "certificate" -> Certificate(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                "desktop" -> {
                    val w = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val h = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    Desktop(w, h)
                }
                "frame" -> Frame
                "ended" -> Ended(parts.getOrElse(1) { "error" }, parts.getOrElse(2) { "" })
                else -> null
            }
        }
    }
}

/** Loads the platform's [RdpEngine] (the native library), or says why it cannot. */
expect fun loadRdpEngine(): RdpAvailability
