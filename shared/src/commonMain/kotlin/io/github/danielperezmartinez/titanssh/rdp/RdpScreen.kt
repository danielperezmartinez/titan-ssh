package io.github.danielperezmartinez.titanssh.rdp

import io.github.danielperezmartinez.titanssh.ssh.SshDirectChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * The remote desktop of an RDP tab (ADR-0028): runs the engine over one SSH
 * channel per connection ([run]), holds the latest [frame] for the view and
 * takes its input. It outlives connections: after a reconnect the view keeps
 * showing the last frame until the new one arrives.
 */
class RdpScreen {

    /**
     * The desktop's pixels: [width] × [height], 4 bytes each in the engine's
     * order ([bgra]). The array is reused and written while the view reads it;
     * [version] grows with every change.
     */
    class Frame(val width: Int, val height: Int, val pixels: ByteArray, val version: Long, val bgra: Boolean)

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    private val _certificate = MutableStateFlow<PendingRdpCertificate?>(null)

    /** A server certificate waiting for the user's decision, before any credential is sent. */
    val certificate: StateFlow<PendingRdpCertificate?> = _certificate.asStateFlow()

    @Volatile
    private var native: RdpNativeSession? = null

    /** Whether input reaches a desktop now. */
    val connected: Boolean get() = native != null && _frame.value != null

    /** Where the pointer is on the desktop, as this client last put it. */
    private var pointerX = 0
    private var pointerY = 0

    /** Moves the pointer to [x], [y] in desktop pixels, kept inside the desktop. */
    fun mouseMove(x: Int, y: Int) {
        val frame = _frame.value
        pointerX = if (frame != null) x.coerceIn(0, frame.width - 1) else x.coerceAtLeast(0)
        pointerY = if (frame != null) y.coerceIn(0, frame.height - 1) else y.coerceAtLeast(0)
        native?.input(RdpInput.MOUSE_MOVE, pointerX, pointerY)
    }

    /** Moves the pointer by [dx], [dy] desktop pixels, as a touchpad does. */
    fun moveBy(dx: Int, dy: Int) {
        mouseMove(pointerX + dx, pointerY + dy)
    }

    /** [button]: 0 left, 1 middle, 2 right. */
    fun mouseButton(button: Int, down: Boolean) {
        native?.input(RdpInput.MOUSE_BUTTON, button, if (down) 1 else 0)
    }

    /** [units]: 120 per wheel notch, positive up (or right when not [vertical]). */
    fun wheel(vertical: Boolean, units: Int) {
        native?.input(RdpInput.WHEEL, if (vertical) 1 else 0, units)
    }

    /** A key by its PC/AT set-1 scancode ([RdpKeys]). */
    fun key(scancode: Int, down: Boolean) {
        native?.input(RdpInput.KEY, scancode, if (down) 1 else 0)
    }

    /**
     * Types [text]. Letters, digits, space, Enter and Tab go as the keys that
     * make them ([RdpKeys.forChar]), which every server understands; the rest
     * as Unicode characters, which Windows takes but X11 servers often ignore.
     */
    fun text(text: String) {
        val session = native ?: return
        var i = 0
        while (i < text.length) {
            val high = text[i]
            val code = if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i++
                ((high.code - 0xD800) shl 10) + (text[i].code - 0xDC00) + 0x10000
            } else {
                high.code
            }
            val key = if (code <= 0xFFFF) RdpKeys.forChar(code.toChar()) else null
            if (key != null) {
                val (scancode, shift) = key
                if (shift) session.input(RdpInput.KEY, RdpKeys.SHIFT, 1)
                session.input(RdpInput.KEY, scancode, 1)
                session.input(RdpInput.KEY, scancode, 0)
                if (shift) session.input(RdpInput.KEY, RdpKeys.SHIFT, 0)
            } else {
                session.input(RdpInput.UNICODE, code, 1)
                session.input(RdpInput.UNICODE, code, 0)
            }
            i++
        }
    }

    /** Releases every key and button still down, e.g. when the tab loses focus. */
    fun releaseAll() {
        native?.input(RdpInput.RELEASE_ALL, 0, 0)
    }

    /** Asks the server to change the desktop to [width] × [height], if it can. */
    fun resizeDesktop(width: Int, height: Int) {
        native?.input(RdpInput.RESIZE, width, height)
    }

    /**
     * One RDP connection over [channel], until it ends: returns how. The
     * certificate goes through [certificates] under [certificateKey], and the
     * user is asked when it is new or changed. Cancelling stops the engine and
     * closes [channel].
     */
    suspend fun run(
        engine: RdpEngine,
        channel: SshDirectChannel,
        params: RdpConnectParams,
        certificates: RdpCertificateStore,
        certificateKey: String,
        /** Called when the desktop is ready, and again if it changes size. */
        onDesktop: () -> Unit = {},
    ): RdpEvent.Ended = withContext(Dispatchers.IO) {
        val session = try {
            engine.start(params)
        } finally {
            params.password.fill(0)
        }
        coroutineScope {
            launch { pumpIn(channel, session) }
            launch { pumpOut(channel, session) }
            try {
                native = session
                events(session, engine.bgra, certificates, certificateKey, onDesktop)
            } finally {
                native = null
                _certificate.value?.reject()
                session.free()
                channel.close()
            }
        }
    }

    /** Server to engine. Blocking: [RdpNativeSession.free] or closing the channel ends it. */
    private fun pumpIn(channel: SshDirectChannel, session: RdpNativeSession) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = channel.read(buffer)
                if (n < 0) break
                if (n > 0 && !session.feed(buffer, n)) return
            }
        } catch (_: Exception) {
            // The channel went away; the engine hears it as an end of stream.
        }
        session.feedEof()
    }

    /** Engine to server. */
    private fun pumpOut(channel: SshDirectChannel, session: RdpNativeSession) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = session.drain(buffer)
                if (n < 0) break
                channel.write(buffer, n)
            }
        } catch (_: Exception) {
            // The channel went away; the engine notices on its read side.
        }
    }

    private suspend fun events(
        session: RdpNativeSession,
        bgra: Boolean,
        certificates: RdpCertificateStore,
        certificateKey: String,
        onDesktop: () -> Unit,
    ): RdpEvent.Ended {
        var width = 0
        var height = 0
        var pixels = ByteArray(0)
        var version = _frame.value?.version ?: 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val text = session.nextEvent(EVENT_WAIT_MILLIS) ?: continue
            when (val event = RdpEvent.parse(text)) {
                is RdpEvent.Certificate ->
                    session.answerCertificate(decide(event, certificates, certificateKey))
                is RdpEvent.Desktop -> {
                    width = event.width
                    height = event.height
                    pixels = ByteArray(width * height * 4)
                    _frame.value = Frame(width, height, pixels, ++version, bgra)
                    // A touchpad starts from the middle, where the user looks first.
                    if (pointerX == 0 && pointerY == 0) mouseMove(width / 2, height / 2)
                    onDesktop()
                }
                RdpEvent.Frame -> {
                    if (width > 0 && session.copyFrame(pixels, width, height) > 0) {
                        _frame.value = Frame(width, height, pixels, ++version, bgra)
                    }
                }
                is RdpEvent.Ended -> return event
                null -> Unit
            }
        }
    }

    /** Whether to go on with [event]'s certificate: trusted already, or the user says so. */
    private suspend fun decide(
        event: RdpEvent.Certificate,
        certificates: RdpCertificateStore,
        key: String,
    ): Boolean {
        val known = certificates.trusted(key)
        if (known == event.sha256) return true
        val pending = PendingRdpCertificate(event.sha256, event.subject, known)
        _certificate.value = pending
        val accepted = try {
            pending.answer.await()
        } finally {
            _certificate.value = null
        }
        if (accepted) certificates.trust(key, event.sha256)
        return accepted
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val EVENT_WAIT_MILLIS = 200
    }
}

/**
 * An RDP server certificate to trust or not (ADR-0028 §6): seen for the first
 * time, or different from the [previous] one trusted for that server.
 */
class PendingRdpCertificate internal constructor(
    /** Lowercase hex SHA-256 of the certificate. */
    val sha256: String,
    val subject: String,
    /** The fingerprint trusted before, when the certificate changed. */
    val previous: String?,
) {
    internal val answer = CompletableDeferred<Boolean>()

    fun accept() {
        answer.complete(true)
    }

    fun reject() {
        answer.complete(false)
    }
}
