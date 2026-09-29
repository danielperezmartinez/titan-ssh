package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    private val agent: AgentLaunch,
    private val onOutput: suspend (ByteArray) -> Unit,
    initialColumns: Int,
    initialRows: Int,
    startOffset: Long = 0,
    /**
     * Called on `HELLO_OK` with whether the agent created the session (a fresh
     * PTY) or re-attached to a live one, before any of its `DATA`. It runs on the
     * reader, so it must not wait for output: launch any follow-up work instead.
     */
    private val onAttached: suspend (fresh: Boolean) -> Unit = {},
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
     * Set when [run] returns without the agent ever serving the session (no
     * `HELLO_OK`) and the agent said why, or its exit status tells: level 3 is
     * not available on this host, which is not a network drop. Null otherwise.
     */
    var unavailable: AgentIssue? = null
        private set

    /** Whether this run got its `HELLO_OK`. */
    private var attached = false

    /** The reason of a `BYE` that refused the session before `HELLO_OK` ("" if it gave none). */
    private var refusal: String? = null

    /**
     * Opens the agent channel, sends `HELLO` and streams its output into
     * [onOutput] until the channel closes (a drop or the agent ending). Returns
     * when the output flow completes. If the agent refused or never answered,
     * [unavailable] says why.
     */
    suspend fun run() = coroutineScope {
        val ch = session.exec(agent.command("--session", safeId))
        stateMutex.withLock { channel = ch }
        // The front reports why it cannot serve on stderr (TITAN_AGENT_ERROR).
        // Reading it also keeps a chatty stderr from filling the channel window.
        val stderr = StringBuilder()
        val stderrJob = launch {
            ch.errors.collect { if (stderr.length < MAX_STDERR) stderr.append(it.decodeToString()) }
        }
        val decoder = AgentProtocol.FrameDecoder()
        try {
            // A front that fails fast may be gone before HELLO lands; its exit
            // status below explains it.
            runCatching { sendFrame(ch, AgentFrame.Hello(safeId, appliedOffset, columns, rows)) }
            ch.output.collect { chunk ->
                for (frame in decoder.feed(chunk)) handle(ch, frame)
            }
        } finally {
            stateMutex.withLock { channel = null }
        }
        if (!attached) {
            withTimeoutOrNull(STDERR_GRACE_MILLIS) { stderrJob.join() }
            val status = runCatching { ch.close() }.getOrNull()
            unavailable = refusal?.let { reason ->
                // An agent that predates the BYE reason refused only when the PTY failed.
                if (reason.isEmpty()) AgentIssue(AgentDiagnostics.E_PTY) else AgentDiagnostics.parseReason(reason)
            } ?: AgentDiagnostics.classifyFrontExit(stderr.toString(), status)
        }
        stderrJob.cancel()
    }

    private suspend fun handle(ch: SshExecChannel, frame: AgentFrame) {
        when (frame) {
            is AgentFrame.Data -> {
                applyData(frame.offset, frame.bytes)
                // The channel may close under a late DATA ([close], or a drop,
                // which ends the output anyway). A lost ACK only means the agent
                // keeps a little more history for the replay.
                runCatching { sendFrame(ch, AgentFrame.Ack(appliedOffset)) }
            }
            is AgentFrame.HelloOk -> {
                attached = true
                val fresh = frame.created ?: isFreshLegacy(frame)
                // A fresh PTY counts its bytes from 0, so an offset carried over
                // from a session that is gone (the host rebooted, or the agent
                // reaped it) would drop all of its output as already applied.
                if (fresh || frame.headOffset < appliedOffset) appliedOffset = 0
                onAttached(fresh)
            }
            // Before HELLO_OK, a BYE refuses the session (its reason says why).
            // After it, BYE carries no output: the DATA offsets drive everything.
            is AgentFrame.Bye -> if (!attached) refusal = frame.reason.orEmpty()
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
        runCatching { sendFrame(ch, AgentFrame.Bye()) }
        runCatching { ch.close() }
    }

    private suspend fun sendFrame(ch: SshExecChannel, frame: AgentFrame) {
        sendMutex.withLock { ch.send(AgentProtocol.encode(frame)) }
    }

    /**
     * Agents that predate [AgentFrame.HelloOk.created] give no flag. A session
     * that has produced nothing yet is taken as fresh: a live one has at least
     * printed its prompt. A fresh one whose shell already wrote something reads
     * as re-attached, which only skips the start scripts; it never re-runs them.
     */
    private fun isFreshLegacy(frame: AgentFrame.HelloOk): Boolean = frame.headOffset == 0L

    companion object {
        /** Enough stderr for the contract line and a shell's complaint. */
        private const val MAX_STDERR = 4096

        /** How long to wait for the rest of stderr once the front's stdout closed. */
        private const val STDERR_GRACE_MILLIS = 1_000L

        /**
         * The agent-side id of saved session [id]: shell-safe (every shell) for
         * the `--session` flag, and what travels in HELLO and `--status`.
         */
        fun sanitizeId(id: String): String {
            val safe = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .ifEmpty { "session" }
            return "titan-$safe"
        }
    }
}
