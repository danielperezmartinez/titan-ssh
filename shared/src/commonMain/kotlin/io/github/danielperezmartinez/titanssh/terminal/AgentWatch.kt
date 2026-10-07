package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * The daemon of one destination user: the agent is per user, so sessions of
 * the same host with another username talk to another agent.
 */
data class AgentKey(val hostname: String, val port: Int, val username: String) {
    /** Stable id used as the persisted map key. */
    val id: String get() = "$username@$hostname:$port"

    /** What the UI shows. */
    val label: String get() = if (port == 22) "$username@$hostname" else "$username@$hostname:$port"

    companion object {
        fun of(endpoint: SshEndpoint) = AgentKey(endpoint.host, endpoint.port, endpoint.username)
    }
}

/** The last [report] seen from an agent and when, on the app's clock. */
@Serializable
data class AgentObservation(val report: AgentStatusReport, val observedAtMs: Long)

/**
 * What the app knows about the agents of its destinations: the last status of
 * each ([observations], by [AgentKey.id]) and the agent sessions still to close
 * ([pendingClose]): sessions whose saved session was deleted while the
 * destination could not be reached, closed on the next connection to it.
 */
@Serializable
data class AgentWatchState(
    val observations: Map<String, AgentObservation> = emptyMap(),
    val pendingClose: Map<String, List<String>> = emptyMap(),
)

/** Persists [AgentWatchState] across app restarts (a JSON file, see `jvmShared`). */
interface AgentWatchStore {
    suspend fun load(): AgentWatchState
    suspend fun save(state: AgentWatchState)
}

/** Builds the platform [AgentWatchStore], next to the config. */
expect fun createAgentWatchStore(): AgentWatchStore

/**
 * Called by a tab once the agent serves it, with a control handle on the same
 * connection ([SessionTab]); the app uses it to close pending sessions and to
 * refresh what it knows about that agent.
 */
fun interface AgentObserver {
    suspend fun onAgentReady(key: AgentKey, control: AgentControl)
}

/**
 * Keeps [state] and applies it: [sync] closes the pending sessions of an agent
 * and records its status. Warnings are derived from [state] by [AgentInsights];
 * nothing here ever closes a session on its own (ADR-0014), only the ones the
 * user deleted.
 */
class AgentWatch(
    private val scope: CoroutineScope,
    private val store: AgentWatchStore,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AgentObserver {

    private val _state = MutableStateFlow(AgentWatchState())
    val state: StateFlow<AgentWatchState> = _state.asStateFlow()

    /** The last failure talking to each agent (not persisted). */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val initial = runCatching { store.load() }.getOrDefault(AgentWatchState())
            mutex.withLock { _state.value = initial }
            loaded.complete(Unit)
        }
    }

    override suspend fun onAgentReady(key: AgentKey, control: AgentControl) {
        runCatching { sync(key, control) }
    }

    /**
     * Closes [key]'s pending sessions and records its status. Failures are kept
     * in [errors]; a pending close that fails stays pending.
     */
    suspend fun sync(key: AgentKey, control: AgentControl): AgentStatusReport {
        loaded.await()
        try {
            for (id in _state.value.pendingClose[key.id].orEmpty()) {
                control.closeSession(id)
                update { it.withoutPending(key, id) }
            }
            val report = control.status()
            record(key, report)
            _errors.value -= key.id
            return report
        } catch (e: Exception) {
            _errors.value += (key.id to (e.message ?: "No se pudo consultar el agente"))
            throw e
        }
    }

    /** Records [report] as [key]'s latest status. */
    suspend fun record(key: AgentKey, report: AgentStatusReport) {
        loaded.await()
        update { st ->
            val observed = st.copy(observations = st.observations + (key.id to AgentObservation(report, clock())))
            // A running agent that no longer lists a pending session has already
            // closed it (its shell exited, or another device closed it).
            if (report.isRunning) {
                val live = report.sessions.map { it.id }.toSet()
                val left = observed.pendingClose[key.id].orEmpty().filter { it in live }
                observed.withPending(key, left)
            } else {
                observed
            }
        }
    }

    /** Marks [agentSessionId] of [key] to be closed on the next connection to it. */
    suspend fun addPendingClose(key: AgentKey, agentSessionId: String) {
        loaded.await()
        update { st -> st.withPending(key, (st.pendingClose[key.id].orEmpty() + agentSessionId).distinct()) }
    }

    fun recordError(key: AgentKey, message: String) {
        _errors.value += (key.id to message)
    }

    private suspend fun update(change: (AgentWatchState) -> AgentWatchState) {
        val next = mutex.withLock {
            val next = change(_state.value)
            _state.value = next
            next
        }
        runCatching { store.save(next) }
    }
}

private fun AgentWatchState.withPending(key: AgentKey, ids: List<String>): AgentWatchState =
    copy(pendingClose = if (ids.isEmpty()) pendingClose - key.id else pendingClose + (key.id to ids))

private fun AgentWatchState.withoutPending(key: AgentKey, id: String): AgentWatchState =
    withPending(key, pendingClose[key.id].orEmpty() - id)

/**
 * Derivations the UI shows from an [AgentObservation] (decisión visual
 * [[Estado y control del agente en la interfaz]]). They only inform: none of
 * them closes anything.
 */
object AgentInsights {
    /** The memory warning fires above this: the daemon and everything under it. */
    const val MEMORY_WARNING_BYTES: Long = 1L shl 30

    /** A session with no client for this long counts as abandoned. */
    const val ABANDONED_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /** The destination's clock now, estimated from the observation. */
    fun agentNow(obs: AgentObservation, appNowMs: Long): Long =
        obs.report.nowMs + (appNowMs - obs.observedAtMs).coerceAtLeast(0)

    /** The live agent session of saved session [savedSessionId], if the observation lists one. */
    fun sessionOf(obs: AgentObservation, savedSessionId: String): AgentSessionReport? {
        val id = AgentTransport.sanitizeId(savedSessionId)
        return obs.report.sessions.firstOrNull { it.id == id && !it.closed }
    }

    /** How long [session] has had no client, or null while one is attached. */
    fun detachedForMs(obs: AgentObservation, session: AgentSessionReport, appNowMs: Long): Long? =
        session.detachedMs?.let { (agentNow(obs, appNowMs) - it).coerceAtLeast(0) }

    fun isAbandoned(obs: AgentObservation, session: AgentSessionReport, appNowMs: Long): Boolean =
        (detachedForMs(obs, session, appNowMs) ?: 0) >= ABANDONED_AFTER_MS

    fun isOverMemory(obs: AgentObservation): Boolean =
        (obs.report.memoryBytes ?: 0) >= MEMORY_WARNING_BYTES

    /** The daemon runs another version than the app (an older agent keeps serving until stopped). */
    fun isOutdated(report: AgentStatusReport, appVersion: String): Boolean =
        report.state == AgentStatusReport.STATE_LEGACY ||
            (report.hasDaemon && report.agent != null && report.agent != appVersion)

    /** [agentMs], a time on the destination's clock, on the app's clock. */
    fun toAppClock(obs: AgentObservation, agentMs: Long): Long =
        agentMs + (obs.observedAtMs - obs.report.nowMs)

    /** The program [path] names: its last path segment, "/usr/bin/bash" → "bash". */
    fun programName(path: String): String =
        path.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /**
     * The details of one agent session, a line each, for the agent panel's
     * unfolded row ([[Detalle de las sesiones en el panel del agente]]). Dates
     * are on the app's clock; [formatDateTime] turns epoch milliseconds into
     * the local "27/09 16:10".
     */
    fun sessionDetails(
        obs: AgentObservation,
        s: AgentSessionReport,
        appNowMs: Long,
        orphan: Boolean = false,
        formatDateTime: (Long) -> String,
    ): List<String> {
        val agentNow = agentNow(obs, appNowMs)
        fun at(agentMs: Long) = formatDateTime(toAppClock(obs, agentMs))
        fun ago(agentMs: Long) = formatDuration((agentNow - agentMs).coerceAtLeast(0))
        val lines = mutableListOf<String>()
        lines += when {
            s.closed -> "La shell terminó: ya no se puede recuperar"
            // Its saved session was deleted, or lives on another device.
            orphan -> "Sigue viva, pero aquí no hay una sesión guardada para abrirla"
            else -> "Recuperable: al abrirla vuelves a esta terminal"
        }
        lines += "creada el ${at(s.createdMs)} · activa desde hace ${ago(s.createdMs)}"
        lines += when {
            s.clients > 1 -> "conectada en ${s.clients} pestañas o dispositivos"
            s.clients == 1 -> "conectada en 1 pestaña o dispositivo"
            s.detachedMs != null -> "sin conectar desde el ${at(s.detachedMs)} (hace ${ago(s.detachedMs)})"
            else -> "sin conectar"
        }
        if (!s.closed && s.shell != null) {
            lines += s.foreground?.let { fg -> "en marcha: $fg" + (s.foregroundPid?.let { " (PID $it)" } ?: "") }
                ?: "en el prompt de la shell"
        }
        s.shell?.let { shell ->
            val size = if (s.cols != null && s.rows != null) " · ${s.cols}×${s.rows}" else ""
            lines += "shell ${programName(shell)}" + (s.shellPid?.let { " (PID $it)" } ?: "") + size
        }
        s.cwd?.let { lines += "directorio: $it" }
        s.title?.takeIf { it.isNotBlank() }?.let { lines += "título: $it" }
        listOfNotNull(
            s.lastOutputMs?.let { "última salida hace ${ago(it)}" },
            s.cpuPercent?.let { "CPU $it %" },
            s.memoryBytes?.let { "memoria ${formatBytes(it)}" },
        ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        lines += "historial guardado " + if (s.bufferBytes > 0) formatBytes(s.bufferBytes) else "vacío"
        return lines
    }

    /**
     * The programs started on the user's desktop with `--desktop-run`
     * (ADR-0019), a line each, newest first: when, what and how it went.
     * Dates are on the app's clock, as in [sessionDetails]. Long command
     * lines are cut to [maxCommand] characters.
     */
    fun desktopRunLines(
        obs: AgentObservation,
        runs: List<AgentDesktopRun>,
        maxCommand: Int = 80,
        formatDateTime: (Long) -> String,
    ): List<String> = runs.map { r ->
        val command = (listOf(programName(r.program)) + r.args.map(::quoteArg)).joinToString(" ")
        val shown = if (command.length > maxCommand) command.take(maxCommand - 1) + "…" else command
        val outcome = when {
            r.error != null -> "no se abrió: ${r.error}"
            r.pid != null -> "PID ${r.pid}"
            else -> "abierto"
        }
        "${formatDateTime(toAppClock(obs, r.timeMs))} · $shown · $outcome"
    }

    /** An argument as a person would type it: quoted when empty or with spaces. */
    private fun quoteArg(arg: String): String =
        if (arg.isEmpty() || arg.any { it.isWhitespace() }) "\"$arg\"" else arg

    /** "3 min", "5 h", "2 días": a duration as the UI shows it. */
    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "menos de 1 min"
            hours < 1 -> "$minutes min"
            days < 2 -> "$hours h"
            else -> "$days días"
        }
    }

    /** "1,2 GB", "180 MB": a size as the UI shows it (powers of 1024). */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "${oneDecimal(mb / 1024)} GB"
            mb >= 10 -> "${mb.toLong()} MB"
            mb >= 1 -> "${oneDecimal(mb)} MB"
            else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
        }
    }

    private fun oneDecimal(v: Double): String {
        val tenths = (v * 10 + 0.5).toLong()
        return "${tenths / 10},${tenths % 10}"
    }
}
