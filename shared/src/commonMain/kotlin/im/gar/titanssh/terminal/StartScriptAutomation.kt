package im.gar.titanssh.terminal

import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.scriptsFor
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SecretStore

/**
 * Wires the start-scripts feature ([[Scripts de inicio por sesión]]) into a tab:
 * on connect it runs the session's initial `cd`, then the enabled
 * [ScriptPhase.ON_SHELL_START] scripts, then the [ScriptPhase.POST_INIT] ones,
 * in order, over the live shell via a [ScriptRunner].
 *
 * Out of scope here (by design): [ScriptPhase.PRE_CONNECT_LOCAL] (no local
 * executor yet) and [ScriptPhase.ON_RECONNECT] (depends on
 * [[Resiliencia de sesión ante microcortes de red]]); [ScriptPhase.ON_DEMAND]
 * scripts are the manual snippets, triggered from the session, not on connect.
 *
 * Secrets are read from the [SecretStore] only at run time and passed straight
 * into the command; they are never written back to the config (ADR-0001).
 */
class StartScriptAutomation(
    private val secretStore: SecretStore,
) : ShellAutomation {

    override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) {
        val session = resolved.session
        val onStart = session.scriptsFor(ScriptPhase.ON_SHELL_START)
        val postInit = session.scriptsFor(ScriptPhase.POST_INIT)
        val scripts = onStart + postInit
        if (session.initialDirectory.isNullOrBlank() && scripts.isEmpty()) return

        val runner = ScriptRunner(
            io = io,
            resolveSecret = { ref -> secretStore.get(SecretRef(ref))?.decodeToString() },
        )
        runner.run(scripts, session.initialDirectory)
    }
}
