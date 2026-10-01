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
 * Client end of a mouse pad session (ADR-0016): `exec`s `titan-agent --input`
 * over [session] and sends it input frames ([AgentFrame.PointerMove],
 * [AgentFrame.Key]…). The agent connects them to its desktop helper, which
 * injects them, and answers [AgentFrame.InputReady] when it is ready and
 * whenever the desktop gets locked or unlocked.
 *
 * There are no offsets or replay: what is sent while the connection is down is
 * lost, and [send] drops it. When the channel closes, the helper releases any
 * button or key left pressed.
 */
class InputTransport(
    private val session: SshSession,
    private val agent: AgentLaunch,
    /** Called on every `INPUT_READY`, on the reader: it must not block. */
    private val onReady: (blocked: Boolean) -> Unit,
) {
    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private var channel: SshExecChannel? = null

    /** Whether this run got its first `INPUT_READY`. */
    var ready: Boolean = false
        private set

    /**
     * Set when [run] returns without the agent ever getting ready, and it said
     * why: no desktop, no injector for this system… Null otherwise, which the
     * tab reads as a drop or a clean end.
     */
    var unavailable: AgentIssue? = null
        private set

    private var refusal: String? = null

    /** Opens the `--input` channel and reads the agent's frames until it closes. */
    suspend fun run() = coroutineScope {
        val ch = session.exec(agent.command("--input"))
        stateMutex.withLock { channel = ch }
        val stderr = StringBuilder()
        val stderrJob = launch {
            ch.errors.collect { if (stderr.length < MAX_STDERR) stderr.append(it.decodeToString()) }
        }
        val decoder = AgentProtocol.FrameDecoder()
        try {
            ch.output.collect { chunk ->
                for (frame in decoder.feed(chunk)) {
                    when (frame) {
                        is AgentFrame.InputReady -> {
                            ready = true
                            onReady(frame.blocked)
                        }
                        is AgentFrame.Bye -> if (!ready) refusal = frame.reason.orEmpty()
                        else -> Unit
                    }
                }
            }
        } finally {
            stateMutex.withLock { channel = null }
        }
        if (!ready) {
            withTimeoutOrNull(STDERR_GRACE_MILLIS) { stderrJob.join() }
            val status = runCatching { ch.close() }.getOrNull()
            unavailable = refusal?.takeIf { it.isNotEmpty() }?.let(AgentDiagnostics::parseReason)
                ?: AgentDiagnostics.classifyFrontExit(stderr.toString(), status)
        }
        stderrJob.cancel()
    }

    /** Sends [frame] if the channel is open; drops it otherwise. */
    suspend fun send(frame: AgentFrame) {
        val ch = stateMutex.withLock { channel } ?: return
        runCatching { sendMutex.withLock { ch.send(AgentProtocol.encode(frame)) } }
    }

    /** Closes the channel; the helper releases what was left pressed. */
    suspend fun close() {
        val ch = stateMutex.withLock { channel } ?: return
        runCatching { ch.close() }
    }

    private companion object {
        /** Enough stderr for the contract line and a shell's complaint. */
        const val MAX_STDERR = 4096

        /** How long to wait for the rest of stderr once the front's stdout closed. */
        const val STDERR_GRACE_MILLIS = 1_000L
    }
}
