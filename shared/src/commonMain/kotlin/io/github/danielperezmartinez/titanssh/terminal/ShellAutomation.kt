package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlinx.coroutines.flow.Flow

/**
 * I/O seam handed to a [ShellAutomation]: an ordered way to [send] text to the
 * live shell plus [output], a decoded, broadcast view of everything the shell
 * emits. The shell is either an [SshShell] or, at level 3, the agent's PTY, fed
 * through `INPUT` frames ([AgentTransport]). Unlike [SshShell.output] (a
 * single-consumer channel already drained by the terminal painter), [output] is
 * a shared tee, so automation can wait for a prompt or a completion sentinel
 * without stealing bytes from the emulator.
 */
class ShellIo internal constructor(
    private val sendBytes: suspend (ByteArray) -> Unit,
    /** Broadcast, UTF-8 decoded view of the shell output (see [SessionTab]). */
    val output: Flow<String>,
    /** Told which multiplexer the automation found, so the tab can show its effective level. */
    private val onMultiplexer: (TerminalMultiplexer.Kind) -> Unit = {},
    /** Finds out which shell reads [send]'s input; asked at most once. */
    private val detectShell: suspend () -> RemoteShell = { RemoteShell.POSIX },
) {
    internal constructor(
        shell: SshShell,
        output: Flow<String>,
        onMultiplexer: (TerminalMultiplexer.Kind) -> Unit = {},
        detectShell: suspend () -> RemoteShell = { RemoteShell.POSIX },
    ) : this({ bytes -> shell.send(bytes) }, output, onMultiplexer, detectShell)

    private var shell: RemoteShell? = null

    /** Sends [text] to the shell verbatim (the caller adds any newline it needs). */
    suspend fun send(text: String) = sendBytes(text.encodeToByteArray())

    /** The destination's shell (POSIX, `cmd.exe` or PowerShell), whose syntax automation must type. */
    suspend fun remoteShell(): RemoteShell = shell ?: detectShell().also { shell = it }

    /** Reports the multiplexer the session runs in ([TerminalMultiplexer.Kind.NONE]: level 1). */
    internal fun reportMultiplexer(kind: TerminalMultiplexer.Kind) = onMultiplexer(kind)
}

/**
 * Hooks run over a tab's live shell, concurrently with terminal painting. The
 * start-scripts feature ([[Scripts de inicio por sesión]]) plugs in here; the
 * default is a no-op so a tab with no automation behaves exactly as before.
 *
 * A hook must return when its scripts finish (or abort); the tab keeps painting
 * output the whole time through the same [ShellIo.output] tee.
 */
interface ShellAutomation {
    /** Runs once, when the tab first opens its shell (connect-time phases). */
    suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection)

    /**
     * Runs after the client transparently reconnects following a network
     * micro-cut ([[Resiliencia de sesión ante microcortes de red]]), over the
     * fresh shell. Honors the session's `ReconnectBehavior` (re-run the start
     * chain / restore only the working directory / do nothing). Default: no-op.
     */
    suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) {}

    /**
     * Level 3 ([[Scripts de inicio por sesión sobre el agente]]): runs when
     * `titan-agent` creates a fresh PTY for the tab, never when it re-attaches to
     * a live one (the agent replays that session as it was). [afterDrop] is true
     * when the fresh PTY replaces one that was lost while the tab was away (the
     * host rebooted, or the agent reaped the idle session), so the hook can honor
     * the session's `ReconnectBehavior` as on level 1. Default: no-op.
     */
    suspend fun onAgentSessionCreated(io: ShellIo, resolved: ResolvedConnection, afterDrop: Boolean) {}

    /**
     * Runs one script the user picked from the tab's scripts menu (an
     * `ON_DEMAND` script of the session or a library script, ADR-0013) over
     * the live shell. Default: no-op.
     */
    suspend fun runOnDemand(io: ShellIo, script: SessionScript) {}

    companion object {
        /** Does nothing: the tab opens a plain shell with no start scripts. */
        val None: ShellAutomation = object : ShellAutomation {
            override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) {}
        }
    }
}
