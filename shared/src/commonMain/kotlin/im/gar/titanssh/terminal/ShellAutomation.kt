package im.gar.titanssh.terminal

import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.ssh.SshShell
import kotlinx.coroutines.flow.Flow

/**
 * I/O seam handed to a [ShellAutomation]: an ordered way to [send] text to the
 * live shell plus [output], a decoded, broadcast view of everything the shell
 * emits. Unlike [SshShell.output] (a single-consumer channel already drained by
 * the terminal painter), [output] is a shared tee, so automation can wait for a
 * prompt or a completion sentinel without stealing bytes from the emulator.
 */
class ShellIo internal constructor(
    private val shell: SshShell,
    /** Broadcast, UTF-8 decoded view of the shell output (see [SessionTab]). */
    val output: Flow<String>,
) {
    /** Sends [text] to the shell verbatim (the caller adds any newline it needs). */
    suspend fun send(text: String) = shell.send(text.encodeToByteArray())
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

    companion object {
        /** Does nothing: the tab opens a plain shell with no start scripts. */
        val None: ShellAutomation = object : ShellAutomation {
            override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) {}
        }
    }
}
