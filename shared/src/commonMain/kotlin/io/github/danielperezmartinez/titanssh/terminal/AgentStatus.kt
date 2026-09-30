package io.github.danielperezmartinez.titanssh.terminal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

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
    // Details added with [[Detalle de las sesiones del agente en el panel]].
    // All optional: an older agent sends none of them.
    /** CPU the shell and its descendants used over the status sample (100 = one core). */
    val cpuPercent: Int? = null,
    /** The program the session started, as a path on the destination. */
    val shell: String? = null,
    val shellPid: Int? = null,
    /**
     * The program in front of the user; null while the shell waits at its
     * prompt. On Windows it is the agent's guess from the process tree.
     */
    val foreground: String? = null,
    val foregroundPid: Int? = null,
    /** The shell's working directory; only Linux reports it. */
    val cwd: String? = null,
    val cols: Int? = null,
    val rows: Int? = null,
    /** When the terminal last wrote anything; null if it never did. */
    val lastOutputMs: Long? = null,
    /** The last window title a program set (OSC 0/2). */
    val title: String? = null,
)

/**
 * What `titan-agent --preview <id> --json` returns: the end of a session's
 * output ([data], at most 16 KiB) and the terminal size it was drawn for.
 * It holds whatever the terminal showed, so it is only kept in memory.
 */
@Serializable
data class AgentPreview(val cols: Int, val rows: Int, val data: String) {

    /**
     * The last non-blank lines of the terminal the preview draws, at most
     * [maxLines], as plain text: the output is replayed into a throwaway
     * [TerminalEmulator] of the session's size.
     */
    fun lines(maxLines: Int = 8): List<String> {
        var bytes = runCatching { Base64.decode(data) }.getOrElse { return emptyList() }
        // A full preview starts mid-stream, maybe inside an escape sequence:
        // skip to the first line break so the replay starts clean.
        if (bytes.size >= FULL_PREVIEW_BYTES) {
            val nl = bytes.indexOf('\n'.code.toByte())
            if (nl >= 0) bytes = bytes.copyOfRange(nl + 1, bytes.size)
        }
        val emulator = TerminalEmulator(cols.coerceIn(1, 500), rows.coerceIn(1, 300), maxScrollback = 0)
        emulator.feed(bytes)
        val shot = emulator.snapshot()
        return shot.screen
            .map { row -> row.joinToString("") { if (it.width == 0) "" else it.text }.trimEnd() }
            .dropLastWhile { it.isEmpty() }
            .dropWhile { it.isEmpty() }
            .takeLast(maxLines)
    }

    companion object {
        /** The agent's cap (`previewBytes` in control.go): a preview this long was cut. */
        const val FULL_PREVIEW_BYTES = 16 * 1024

        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AgentPreview = JSON.decodeFromString(serializer(), text.trim())
    }
}
