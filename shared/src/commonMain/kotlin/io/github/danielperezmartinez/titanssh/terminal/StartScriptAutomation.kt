package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.ReconnectBehavior
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.ScriptPhase
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.effectiveReconnectBehavior
import io.github.danielperezmartinez.titanssh.config.scriptsFor
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wires the start-scripts feature ([[Scripts de inicio por sesión]]) into a tab:
 * on connect it runs the session's initial `cd`, then the enabled
 * [ScriptPhase.ON_SHELL_START] scripts, then the [ScriptPhase.POST_INIT] ones,
 * in order, over the live shell via a [ScriptRunner].
 *
 * ## Resilience (ADR-0003)
 * - **Level 1** ([[Resiliencia de sesión ante microcortes de red]]): on a
 *   transparent reconnect ([onReconnected]) it honors the session's
 *   `ReconnectBehavior` — re-run the whole start chain, restore only the working
 *   directory (`cd`), or do nothing.
 * - **Level 2** ([[Resiliencia nivel 2 auto-tmux o screen]]): when the session's
 *   [ResilienceLevel] is `AUTO_MULTIPLEXER` (or higher) and the destination has
 *   tmux/screen, it wraps the shell in a named multiplexer session via a
 *   [TerminalMultiplexer]. The remote process then survives a drop, and a
 *   reconnect *re-attaches* to the live session instead of replaying scripts. If
 *   no multiplexer is present it degrades cleanly to level 1.
 * - **Level 3** ([[Scripts de inicio por sesión sobre el agente]]): the start
 *   chain runs only when `titan-agent` creates a fresh PTY
 *   ([onAgentSessionCreated]); re-attaching to a live one replays nothing.
 *
 * [ScriptPhase.ON_DEMAND] scripts never run on connect: the user launches them
 * from the tab's scripts menu ([runOnDemand]). Out of scope here (by design):
 * [ScriptPhase.PRE_CONNECT_LOCAL] (no local executor yet).
 *
 * The commands it types follow the destination's shell ([ShellIo.remoteShell]):
 * a Windows destination gets `cmd.exe` or PowerShell syntax and never the
 * multiplexer (tmux/screen are Unix-only).
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
    /** Settle time after entering a multiplexer, before running scripts inside it. */
    private val multiplexerSettleMillis: Long = 400,
) : ShellAutomation {

    override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) {
        val session = resolved.session
        awaitReady(io)
        if (usesMultiplexer(session)) {
            when (enterMultiplexer(io, session)) {
                // A pre-existing multiplexer session already holds the work; do not
                // re-run the start scripts on top of it.
                TerminalMultiplexer.Attach.ATTACHED -> return
                TerminalMultiplexer.Attach.CREATED -> {
                    delay(multiplexerSettleMillis)
                    runScripts(io, connectScripts(session), session.initialDirectory)
                }
                // No multiplexer available: behave like level 1 / plain start.
                null -> runScripts(io, connectScripts(session), session.initialDirectory)
            }
        } else {
            runScripts(io, connectScripts(session), session.initialDirectory)
        }
    }

    /**
     * After a transparent reconnect, either re-attaches to the surviving
     * multiplexer session (level 2) or replays automation per the session's
     * [ReconnectBehavior] (level 1):
     * - [ReconnectBehavior.NONE]: nothing runs.
     * - [ReconnectBehavior.RESTORE_CD_ONLY]: only the initial `cd`.
     * - [ReconnectBehavior.RERUN_ALL]: the whole start chain — `cd`, the
     *   connect-time scripts and the [ScriptPhase.ON_RECONNECT] scripts, in order.
     */
    override suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) {
        val session = resolved.session
        awaitReady(io)
        if (usesMultiplexer(session)) {
            when (enterMultiplexer(io, session)) {
                // The process survived inside the multiplexer: re-attaching is the
                // whole recovery, nothing to replay.
                TerminalMultiplexer.Attach.ATTACHED -> return
                // The multiplexer was lost (server gone): rebuild via level-1 replay.
                TerminalMultiplexer.Attach.CREATED -> replayForReconnect(io, session)
                null -> replayForReconnect(io, session)
            }
        } else {
            replayForReconnect(io, session)
        }
    }

    /**
     * Level 3: the agent created a fresh PTY. On the tab's first connection this
     * is the plain start chain; after a drop the old PTY is gone, so it rebuilds
     * per the session's [ReconnectBehavior], like level 2 does when it finds its
     * multiplexer session lost. The agent's PTY already outlives drops, so this
     * never wraps it in tmux/screen.
     */
    override suspend fun onAgentSessionCreated(io: ShellIo, resolved: ResolvedConnection, afterDrop: Boolean) {
        val session = resolved.session
        awaitReady(io)
        if (afterDrop) replayForReconnect(io, session)
        else runScripts(io, connectScripts(session), session.initialDirectory)
    }

    /**
     * Sends [script] without waiting for it to finish: the user watches it run,
     * and awaiting would print the completion sentinel into their terminal. The
     * delay, `expect`, env vars and secrets still apply.
     */
    override suspend fun runOnDemand(io: ShellIo, script: SessionScript) {
        val fireAndForget = script.copy(behavior = script.behavior.copy(waitForCompletion = false))
        runScripts(io, listOf(fireAndForget), initialDirectory = null)
    }

    private suspend fun replayForReconnect(io: ShellIo, session: Session) {
        when (session.effectiveReconnectBehavior()) {
            ReconnectBehavior.NONE -> return
            ReconnectBehavior.RESTORE_CD_ONLY ->
                runScripts(io, scripts = emptyList(), initialDirectory = session.initialDirectory)
            ReconnectBehavior.RERUN_ALL ->
                runScripts(
                    io,
                    connectScripts(session) + session.scriptsFor(ScriptPhase.ON_RECONNECT),
                    session.initialDirectory,
                )
        }
    }

    /**
     * Detects a multiplexer and enters (attach-or-create) the session's named
     * multiplexer session. Returns whether it attached to a live one or created a
     * fresh one, or `null` if the destination has no multiplexer.
     */
    private suspend fun enterMultiplexer(io: ShellIo, session: Session): TerminalMultiplexer.Attach? {
        // tmux/screen are Unix tools, and the probe itself is POSIX syntax.
        if (io.remoteShell() != RemoteShell.POSIX) {
            io.reportMultiplexer(TerminalMultiplexer.Kind.NONE)
            return null
        }
        val mux = TerminalMultiplexer(io)
        val kind = mux.detect()
        io.reportMultiplexer(kind)
        if (kind == TerminalMultiplexer.Kind.NONE) return null
        val name = mux.sessionName(session.id)
        val existed = mux.sessionExists(kind, name)
        mux.enter(kind, name)
        return if (existed) TerminalMultiplexer.Attach.ATTACHED else TerminalMultiplexer.Attach.CREATED
    }

    private fun connectScripts(session: Session): List<SessionScript> =
        session.scriptsFor(ScriptPhase.ON_SHELL_START) + session.scriptsFor(ScriptPhase.POST_INIT)

    /**
     * Level 2 routes through the multiplexer; BASE stays on plain client-side
     * reconnection. A level-3/`AGENT` session that drives `titan-agent` goes
     * through [onAgentSessionCreated] instead and never reaches this check —
     * unless it *degraded* (no agent deployer, or the install failed), in which
     * case it lands on the shell hooks and, like level 2, uses the multiplexer.
     */
    private fun usesMultiplexer(session: Session): Boolean =
        session.resilienceLevel.ordinal >= ResilienceLevel.AUTO_MULTIPLEXER.ordinal

    private suspend fun awaitReady(io: ShellIo) {
        // Wait for the shell to print its prompt/banner so early input isn't lost
        // (a remote PTY drops input written before it starts reading stdin). On a
        // reconnect this is the *new* shell's first output: the tab hands us a
        // fresh tee with no stale replay from before the drop.
        withTimeoutOrNull(readyTimeoutMillis) { io.output.first { it.isNotEmpty() } }
    }

    private suspend fun runScripts(
        io: ShellIo,
        scripts: List<SessionScript>,
        initialDirectory: String?,
    ) {
        if (initialDirectory.isNullOrBlank() && scripts.isEmpty()) return
        val runner = ScriptRunner(
            io = io,
            resolveSecret = { ref -> secretStore.get(SecretRef(ref))?.decodeToString() },
            shell = io.remoteShell(),
        )
        runner.run(scripts, initialDirectory)
    }
}
