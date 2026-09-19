package im.gar.titanssh.terminal

import im.gar.titanssh.config.ReconnectBehavior
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.effectiveReconnectBehavior
import im.gar.titanssh.config.scriptsFor
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wires the start-scripts feature ([[Scripts de inicio por sesión]]) into a tab:
 * on connect it runs the session's initial `cd`, then the enabled
 * [ScriptPhase.ON_SHELL_START] scripts, then the [ScriptPhase.POST_INIT] ones,
 * in order, over the live shell via a [ScriptRunner].
 *
 * On a transparent reconnect after a micro-cut ([onReconnected],
 * [[Resiliencia de sesión ante microcortes de red]]) it honors the session's
 * `ReconnectBehavior`: re-run the whole start chain, restore only the working
 * directory (`cd`), or do nothing.
 *
 * Out of scope here (by design): [ScriptPhase.PRE_CONNECT_LOCAL] (no local
 * executor yet); [ScriptPhase.ON_DEMAND] scripts are the manual snippets,
 * triggered from the session, not on connect.
 *
 * Secrets are read from the [SecretStore] only at run time and passed straight
 * into the command; they are never written back to the config (ADR-0001).
 *
 * Before sending anything it waits for the shell to be ready (its first output,
 * i.e. the prompt): a remote PTY drops input written before the shell starts
 * reading stdin, so an eager first command could otherwise be lost.
 */
class StartScriptAutomation(
    private val secretStore: SecretStore,
    /** How long to wait for the shell's first output before running anyway. */
    private val readyTimeoutMillis: Long = 10_000,
) : ShellAutomation {

    override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) {
        val session = resolved.session
        val onStart = session.scriptsFor(ScriptPhase.ON_SHELL_START)
        val postInit = session.scriptsFor(ScriptPhase.POST_INIT)
        runChain(io, onStart + postInit, session.initialDirectory)
    }

    /**
     * After a transparent reconnect, replays automation according to the
     * session's [ReconnectBehavior]:
     * - [ReconnectBehavior.NONE]: nothing runs.
     * - [ReconnectBehavior.RESTORE_CD_ONLY]: only the initial `cd`.
     * - [ReconnectBehavior.RERUN_ALL]: the whole start chain — `cd`, the
     *   connect-time scripts and the [ScriptPhase.ON_RECONNECT] scripts, in order.
     */
    override suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) {
        val session = resolved.session
        when (session.effectiveReconnectBehavior()) {
            ReconnectBehavior.NONE -> return
            ReconnectBehavior.RESTORE_CD_ONLY ->
                runChain(io, scripts = emptyList(), initialDirectory = session.initialDirectory)
            ReconnectBehavior.RERUN_ALL -> {
                val scripts = session.scriptsFor(ScriptPhase.ON_SHELL_START) +
                    session.scriptsFor(ScriptPhase.POST_INIT) +
                    session.scriptsFor(ScriptPhase.ON_RECONNECT)
                runChain(io, scripts, session.initialDirectory)
            }
        }
    }

    private suspend fun runChain(
        io: ShellIo,
        scripts: List<im.gar.titanssh.config.SessionScript>,
        initialDirectory: String?,
    ) {
        if (initialDirectory.isNullOrBlank() && scripts.isEmpty()) return

        // Wait for the shell to print its prompt/banner so early input isn't lost
        // (a remote PTY drops input written before it starts reading stdin). On a
        // reconnect this is the *new* shell's first output: the tab hands us a
        // fresh tee with no stale replay from before the drop.
        withTimeoutOrNull(readyTimeoutMillis) { io.output.first { it.isNotEmpty() } }

        val runner = ScriptRunner(
            io = io,
            resolveSecret = { ref -> secretStore.get(SecretRef(ref))?.decodeToString() },
        )
        runner.run(scripts, initialDirectory)
    }
}
