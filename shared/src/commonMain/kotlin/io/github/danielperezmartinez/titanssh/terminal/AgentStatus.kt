package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What `titan-agent --status --json` reports about the user's daemon on a
 * destination ([[Transparencia y control del agente en el destino]]). Mirrors
 * `statusReport` in `agent/cmd/titan-agent/control.go` (schema 1); keep both
 * in sync. Times are Unix milliseconds on the **destination's** clock, so they
 * are only compared with [nowMs], never with the app's clock.
 */
@Serializable
data class AgentStatusReport(
    val schema: Int = 1,
    /** One of [STATE_RUNNING], [STATE_STOPPED], [STATE_LEGACY], [STATE_UNREACHABLE]. */
    val state: String,
    /** Version of the running daemon (from agent.json for a legacy one). */
    val agent: String? = null,
    /** Version of the binary that answered the command. */
    val cli: String = "",
    val pid: Int? = null,
    val os: String? = null,
    val arch: String? = null,
    val nowMs: Long,
    val startedMs: Long? = null,
    /** The daemon and every process under it; null when the destination cannot measure it. */
    val memoryBytes: Long? = null,
    val sessions: List<AgentSessionReport> = emptyList(),
) {
    val isRunning: Boolean get() = state == STATE_RUNNING

    /** A daemon holds the user's lock, whether or not it can report its sessions. */
    val hasDaemon: Boolean get() = state == STATE_RUNNING || state == STATE_LEGACY || state == STATE_UNREACHABLE

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        /** The daemon predates the control connection: it cannot list or close sessions. */
        const val STATE_LEGACY = "legacy"
        /** A daemon holds the lock but does not answer (an agent from before it republished its state). */
        const val STATE_UNREACHABLE = "unreachable"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Parses the `--status --json` output (one JSON object). */
        fun parse(text: String): AgentStatusReport = JSON.decodeFromString(serializer(), text.trim())
    }
}

/** One session held by the daemon. */
@Serializable
data class AgentSessionReport(
    /** The agent-side id, `titan-<sanitized saved-session id>` ([AgentTransport.sanitizeId]). */
    val id: String,
    val createdMs: Long,
    val lastUsedMs: Long,
    /** When the last client left; null while one is attached. */
    val detachedMs: Long? = null,
    val clients: Int = 0,
    /** The shell exited; the daemon drops the session soon. */
    val closed: Boolean = false,
    val bufferBytes: Long = 0,
    /** The session's shell and its descendants; null when not measurable. */
    val memoryBytes: Long? = null,
)
