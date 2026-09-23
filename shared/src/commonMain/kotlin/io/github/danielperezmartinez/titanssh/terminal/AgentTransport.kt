package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client end of the resilience level-3 protocol (ADR-0008): `exec`s an already
 * installed `titan-agent` over an [SshExecChannel] and speaks [AgentProtocol]
 * with it. [run] sends the opening `HELLO` (asking for replay from
 * [appliedOffset]) and then pumps the agent's `DATA` into [onOutput] until the
 * channel closes; [sendInput]/[resize] send frames the other way.
 *
 * Offsets make reconnection exact: [appliedOffset] tracks how many bytes have
 * been fed to the emulator, so a reconnect replays only what the client missed.
 * Overlapping replay is de-duplicated and a gap (buffer trimmed past the client)
 * is fed as-is. All writes to the channel are serialized so frames never
 * interleave on the wire.
 */
class AgentTransport(
    private val session: SshSession,
    agentSessionId: String,
    private val agentPath: String,
    private val onOutput: suspend (ByteArray) -> Unit,
    initialColumns: Int,
    initialRows: Int,
    startOffset: Long = 0,
) {
    /** Bytes fed to the emulator so far; survives reconnects when the tab reuses it. */
    var appliedOffset: Long = startOffset
        private set

    private val safeId = sanitizeId(agentSessionId)
    private var columns = initialColumns
    private var rows = initialRows

    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private var channel: SshExecChannel? = null

    /**
     * Opens the agent channel, sends `HELLO` and streams its output into
     * [onOutput] until the channel closes (a drop or the agent ending). Returns
     * when the output flow completes.
     */
    suspend fun run() {
        val ch = session.exec("$agentPath --session $safeId")
        stateMutex.withLock { channel = ch }
        sendFrame(ch, AgentFrame.Hello(safeId, appliedOffset, columns, rows))
        val decoder = AgentProtocol.FrameDecoder()
        try {
            ch.output.collect { chunk ->
                for (frame in decoder.feed(chunk)) handle(ch, frame)
            }
        } finally {
            stateMutex.withLock { channel = null }
        }
    }

    private suspend fun handle(ch: SshExecChannel, frame: AgentFrame) {
        when (frame) {
            is AgentFrame.Data -> {
                applyData(frame.offset, frame.bytes)
                sendFrame(ch, AgentFrame.Ack(appliedOffset))
            }
            // HELLO_OK / BYE carry no output; the DATA offsets drive everything.
            else -> Unit
        }
    }

    private suspend fun applyData(offset: Long, bytes: ByteArray) {
        val end = offset + bytes.size
        if (end <= appliedOffset) return // fully-overlapping replay: already applied
        val start = if (offset < appliedOffset) (appliedOffset - offset).toInt() else 0
        onOutput(if (start == 0) bytes else bytes.copyOfRange(start, bytes.size))
        appliedOffset = end
    }

    /** Sends raw client input to the remote PTY via an `INPUT` frame. */
    suspend fun sendInput(bytes: ByteArray) {
        val ch = stateMutex.withLock { channel } ?: return
        sendFrame(ch, AgentFrame.Input(bytes))
    }

    /** Forwards a new terminal size via a `RESIZE` frame. */
    suspend fun resize(cols: Int, rows: Int) {
        columns = cols
        this.rows = rows
        val ch = stateMutex.withLock { channel } ?: return
        sendFrame(ch, AgentFrame.Resize(cols, rows))
    }

    /** Says goodbye and closes the channel; the agent keeps the session alive. */
    suspend fun close() {
        val ch = stateMutex.withLock { channel } ?: return
        runCatching { sendFrame(ch, AgentFrame.Bye) }
        runCatching { ch.close() }
    }

    private suspend fun sendFrame(ch: SshExecChannel, frame: AgentFrame) {
        sendMutex.withLock { ch.send(AgentProtocol.encode(frame)) }
    }

    private companion object {
        /** Keeps the id shell-safe for the `--session` flag (it also travels in HELLO). */
        fun sanitizeId(id: String): String {
            val safe = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .ifEmpty { "session" }
            return "titan-$safe"
        }
    }
}
