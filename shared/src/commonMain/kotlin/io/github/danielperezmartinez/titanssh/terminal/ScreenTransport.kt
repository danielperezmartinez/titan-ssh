package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Client end of a remote desktop session (ADR-0029): `exec`s `titan-agent
 * --screen` over [session], asks for [quality] with `SCREEN_HELLO`, feeds the
 * screen frames into [screen] and acknowledges each frame once applied, which
 * is what lets the agent send the next ones. Input frames go the other way on
 * the same channel ([send]). While the destination's screen is out of reach
 * (Windows locked, UAC), the agent says so with `INPUT_READY` ([onBlocked]).
 *
 * There is no replay: a new connection starts with the screen's size and a
 * whole frame, and [screen] keeps showing the last image meanwhile.
 */
class ScreenTransport(
    private val session: SshSession,
    private val agent: AgentLaunch,
    private val screen: RemoteScreen,
    private val quality: Int,
    /** Called when the screen goes out of reach and comes back, on the reader: it must not block. */
    private val onBlocked: (blocked: Boolean) -> Unit = {},
    /**
     * Called on the first frame of this connection, or on the first blocked
     * state if the screen is out of reach from the start, on the reader: it
     * must not block.
     */
    private val onReady: () -> Unit,
) {
    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private var channel: SshExecChannel? = null

    /** Whether this run got its first frame. */
    var ready: Boolean = false
        private set

    /** Why [run] ended without the agent ever sending the screen; null otherwise. */
    var unavailable: AgentIssue? = null
        private set

    private var refusal: String? = null

    /** Opens the `--screen` channel and reads the agent's frames until it closes. */
    suspend fun run() = coroutineScope {
        val ch = session.exec(agent.command("--screen"))
        stateMutex.withLock { channel = ch }
        val stderr = StringBuilder()
        val stderrJob = launch {
            ch.errors.collect { if (stderr.length < MAX_STDERR) stderr.append(it.decodeToString()) }
        }
        val decoder = AgentProtocol.FrameDecoder()
        try {
            send(AgentFrame.ScreenHello(ScreenCodec.VERSION, quality))
            ch.output.collect { chunk ->
                for (frame in decoder.feed(chunk)) {
                    if (frame is AgentFrame.Bye) {
                        if (!ready) refusal = frame.reason.orEmpty()
                        continue
                    }
                    if (frame is AgentFrame.InputReady) {
                        markReady()
                        onBlocked(frame.blocked)
                        continue
                    }
                    val ack = screen.apply(frame)
                    if (ack != null) {
                        markReady()
                        send(AgentFrame.ScreenAck(ack))
                    }
                }
            }
        } catch (e: AgentProtocolException) {
            // A frame this client cannot use: the connection is no good.
            if (!ready) unavailable = AgentIssue(AgentDiagnostics.E_PROTOCOL, e.message ?: "bad screen frame")
            runCatching { ch.close() }
        } finally {
            stateMutex.withLock { channel = null }
        }
        if (!ready && unavailable == null) {
            withTimeoutOrNull(STDERR_GRACE_MILLIS) { stderrJob.join() }
            val status = runCatching { ch.close() }.getOrNull()
            unavailable = refusal?.takeIf { it.isNotEmpty() }?.let(AgentDiagnostics::parseReason)
                ?: AgentDiagnostics.classifyFrontExit(stderr.toString(), status)
        }
        stderrJob.cancel()
    }

    private fun markReady() {
        if (!ready) {
            ready = true
            onReady()
        }
    }

    /** Sends [frame] if the channel is open; drops it otherwise. */
    suspend fun send(frame: AgentFrame) {
        val ch = stateMutex.withLock { channel } ?: return
        runCatching { sendMutex.withLock { ch.send(AgentProtocol.encode(frame)) } }
    }

    /** Closes the channel; the agent releases what was left pressed. */
    suspend fun close() {
        val ch = stateMutex.withLock { channel } ?: return
        runCatching { ch.close() }
    }

    private companion object {
        const val MAX_STDERR = 4096
        const val STDERR_GRACE_MILLIS = 1_000L
    }
}
