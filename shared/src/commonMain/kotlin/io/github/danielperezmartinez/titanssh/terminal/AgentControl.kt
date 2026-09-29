package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession

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

    /** `--stop`: the daemon closes every session and exits. */
    suspend fun stop() {
        run("--stop").requireSuccess("--stop")
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
