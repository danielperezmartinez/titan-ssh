package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlin.io.encoding.Base64

/**
 * Runs the agent's control commands ([[Transparencia y control del agente en el
 * destino]]) on an open [session], through the installed binary [launch]: each
 * is one `exec` of the CLI, which talks to the daemon over its own loopback
 * control connection on the destination. Nothing here touches the level-3
 * framed protocol, so it can run alongside a live [AgentTransport] on the same
 * connection.
 */
class AgentControl(private val session: SshSession, private val launch: AgentLaunch) {

    /** `--status --json`: the daemon and its sessions as they are now. */
    suspend fun status(): AgentStatusReport {
        val result = run("--status", "--json")
        result.requireSuccess("--status")
        return try {
            AgentStatusReport.parse(result.stdout)
        } catch (e: Exception) {
            throw AgentControlException("Respuesta del agente ilegible: ${e.message}", e)
        }
    }

    /**
     * `--close-session`: ends the agent session [agentSessionId] (the
     * `titan-…` id, see [AgentTransport.sanitizeId]). Closing one that does not
     * exist, or with no daemon running, succeeds: it is gone either way.
     */
    suspend fun closeSession(agentSessionId: String) {
        run("--close-session", agentSessionId).requireSuccess("--close-session")
    }

    /**
     * `--rename-session`: gives agent session [agentSessionId] the user's
     * [name]; a blank one takes its name away. The name travels base64url so
     * no shell on the destination reinterprets it. An agent from before names
     * does not know the command and says to update it.
     */
    suspend fun renameSession(agentSessionId: String, name: String) {
        val encoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(name.trim().encodeToByteArray())
        val args = if (encoded.isEmpty()) arrayOf("--rename-session", agentSessionId) else arrayOf("--rename-session", agentSessionId, "--name-base64", encoded)
        val result = run(*args)
        if (result.stderr.contains("flag provided but not defined")) {
            throw AgentControlException("El agente del destino es de una versión sin nombres: actualízalo desde su panel")
        }
        result.requireSuccess("--rename-session")
    }

    /**
     * `--preview`: the end of agent session [agentSessionId]'s output, or
     * null when the daemon does not hold it. An agent from before `--preview`
     * fails the command, which throws like any other failure.
     */
    suspend fun preview(agentSessionId: String): AgentPreview? {
        val result = run("--preview", agentSessionId, "--json")
        if (result.stderr.contains("no such session")) return null
        result.requireSuccess("--preview")
        return try {
            AgentPreview.parse(result.stdout)
        } catch (e: Exception) {
            throw AgentControlException("Respuesta del agente ilegible: ${e.message}", e)
        }
    }

    /** `--stop`: the daemon closes every session and exits. */
    suspend fun stop() {
        run("--stop").requireSuccess("--stop")
    }

    /**
     * `--restart`: the installed binary replaces the running daemon with its
     * own version and every session keeps running in its host (ADR-0022).
     * Only for a daemon whose status says [AgentStatusReport.sessionHosts].
     */
    suspend fun restart() {
        run("--restart").requireSuccess("--restart")
    }

    /**
     * `--remove-desktop`: stops the mouse pad's desktop helper and deletes its
     * scheduled task and executable copies (ADR-0016). Nothing to remove succeeds.
     */
    suspend fun removeDesktop() {
        run("--remove-desktop").requireSuccess("--remove-desktop")
    }

    private suspend fun run(vararg args: String): ExecResult = session.execCollect(launch.command(*args))

    private fun ExecResult.requireSuccess(what: String) {
        // Some servers send no exit status; the output then decides.
        if (exitStatus == null || exitStatus == 0) return
        val reason = stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: "salió con el código $exitStatus"
        throw AgentControlException("titan-agent $what falló: $reason")
    }
}

/** A control command failed on the destination, or its reply was unreadable. */
class AgentControlException(message: String, cause: Throwable? = null) : Exception(message, cause)
