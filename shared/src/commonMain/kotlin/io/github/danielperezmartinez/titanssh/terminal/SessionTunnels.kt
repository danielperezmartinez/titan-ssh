package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Tunnel
import io.github.danielperezmartinez.titanssh.config.TunnelType
import io.github.danielperezmartinez.titanssh.ssh.ForwardFailure
import io.github.danielperezmartinez.titanssh.ssh.ForwardProblem
import io.github.danielperezmartinez.titanssh.ssh.PortForward
import io.github.danielperezmartinez.titanssh.ssh.SshForward
import io.github.danielperezmartinez.titanssh.ssh.SshForwardFailed
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a tab's tunnel stands. */
enum class TunnelState {
    /** Not open: the tab is connecting, reconnecting or has ended. */
    WAITING,

    /** Listening. [TunnelStatus.detail] may carry the last connection through it that failed. */
    ACTIVE,

    /** Could not be opened on this connection; [TunnelStatus.detail] says why. */
    FAILED,
}

/** One enabled tunnel of a tab, as the tab strip shows it. */
data class TunnelStatus(val tunnel: Tunnel, val state: TunnelState, val detail: String? = null)

/**
 * The enabled tunnels of one tab ([[Ejecutar los túneles de las sesiones]]).
 * They go over the tab's SSH connection at every resilience level (on level 3
 * too: the agent only carries the PTY), so [SessionTab] opens them on each
 * connection and closes them when it ends; a micro-cut reopens them on the
 * new one. A tunnel that fails is reported in [status] and never ends the
 * session.
 */
internal class SessionTunnels(tunnels: List<Tunnel>) {

    private val enabled = tunnels.filter { it.enabled }

    private val _status = MutableStateFlow(enabled.map { TunnelStatus(it, TunnelState.WAITING) })
    val status: StateFlow<List<TunnelStatus>> = _status.asStateFlow()

    private val lock = Mutex()
    private val open = mutableListOf<SshForward>()

    /** Ids of tunnels that failed for a reason that may clear up by itself; guarded by [lock]. */
    private val retryable = mutableSetOf<String>()

    /** Opens every enabled tunnel on [session]; each failure lands in [status]. */
    suspend fun open(session: SshSession) = lock.withLock {
        for (tunnel in enabled) {
            tryOpen(session, tunnel)
            currentCoroutineContext().ensureActive()
        }
    }

    /**
     * Retries, while [session] lives, the tunnels whose port was taken, here
     * or on the server. After a micro-cut the server may still hold a remote
     * forward's port for the old connection until it notices the drop; a local
     * port may be freed by the user. The caller cancels it when the connection
     * ends.
     */
    suspend fun retryFailed(session: SshSession) {
        var attempt = 0
        while (true) {
            if (lock.withLock { retryable.isEmpty() }) return
            delay(if (attempt++ < FAST_RETRIES) FAST_RETRY_MILLIS else SLOW_RETRY_MILLIS)
            lock.withLock {
                // [close] empties the set, so a retry never opens on a dead connection.
                for (tunnel in enabled.filter { it.id in retryable }) tryOpen(session, tunnel)
            }
        }
    }

    /** Opens [tunnel] on [session] and records the outcome. Call with [lock] held. */
    private suspend fun tryOpen(session: SshSession, tunnel: Tunnel) {
        retryable -= tunnel.id
        val forward = tunnel.toPortForward()
        if (forward == null) {
            set(tunnel.id, TunnelState.FAILED, "falta el destino")
            return
        }
        try {
            // Not cancellable once started, so a tab closed meanwhile still
            // gets the handle into [open] and releases the port in [close].
            val handle = withContext(NonCancellable) {
                session.openForward(forward) { problem -> onProblem(tunnel, problem) }
            }
            open += handle
            set(tunnel.id, TunnelState.ACTIVE, null)
        } catch (e: SshForwardFailed) {
            val transient = e.reason == ForwardFailure.PORT_IN_USE || e.reason == ForwardFailure.REFUSED_BY_SERVER
            if (transient) retryable += tunnel.id
            set(tunnel.id, TunnelState.FAILED, describe(tunnel, e.reason) + if (transient) " · se reintenta" else "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            set(tunnel.id, TunnelState.FAILED, "no se pudo abrir: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Closes the open tunnels (idempotent); they wait for the next connection. */
    suspend fun close() = withContext(NonCancellable) {
        lock.withLock {
            retryable.clear()
            open.forEach { runCatching { it.close() } }
            open.clear()
            _status.update { list -> list.map { it.copy(state = TunnelState.WAITING, detail = null) } }
        }
    }

    private fun onProblem(tunnel: Tunnel, problem: ForwardProblem) {
        val detail = describe(tunnel, problem)
        _status.update { list ->
            list.map { if (it.tunnel.id == tunnel.id && it.state == TunnelState.ACTIVE) it.copy(detail = detail) else it }
        }
    }

    private fun set(id: String, state: TunnelState, detail: String?) {
        _status.update { list -> list.map { if (it.tunnel.id == id) it.copy(state = state, detail = detail) else it } }
    }

    internal companion object {
        const val FAST_RETRIES = 12
        const val FAST_RETRY_MILLIS = 5_000L
        const val SLOW_RETRY_MILLIS = 30_000L

        /** The engine's view of [this]; null when a forward lacks its destination. */
        fun Tunnel.toPortForward(): PortForward? {
            val host = listenHost.ifBlank { "127.0.0.1" }
            val destHost = destinationHost?.takeIf { it.isNotBlank() }
            val destPort = destinationPort
            return when (type) {
                TunnelType.DYNAMIC_SOCKS -> PortForward.DynamicSocks(host, listenPort)
                TunnelType.LOCAL ->
                    if (destHost == null || destPort == null) null
                    else PortForward.Local(host, listenPort, destHost, destPort)
                TunnelType.REMOTE ->
                    if (destHost == null || destPort == null) null
                    else PortForward.Remote(host, listenPort, destHost, destPort)
            }
        }

        fun describe(tunnel: Tunnel, reason: ForwardFailure): String {
            val listen = "${tunnel.listenHost}:${tunnel.listenPort}"
            return when (reason) {
                ForwardFailure.PORT_IN_USE -> "el puerto ${tunnel.listenPort} ya está en uso en este equipo"
                ForwardFailure.PERMISSION_DENIED ->
                    "sin permiso para escuchar en el puerto ${tunnel.listenPort} (los menores de 1024 suelen necesitar privilegios)"
                ForwardFailure.BAD_ADDRESS -> "la dirección de escucha ${tunnel.listenHost} no vale en este equipo"
                ForwardFailure.REFUSED_BY_SERVER ->
                    "el servidor rechaza escuchar en $listen (reenvío desactivado, puerto ocupado o privilegiado)"
                ForwardFailure.OTHER -> "no se pudo abrir el túnel en $listen"
            }
        }

        fun describe(tunnel: Tunnel, problem: ForwardProblem): String = when (problem.kind) {
            ForwardProblem.Kind.PROHIBITED -> "el servidor no permite reenvíos (AllowTcpForwarding)"
            ForwardProblem.Kind.UNREACHABLE ->
                if (tunnel.type == TunnelType.REMOTE) "no se pudo conectar con ${problem.target} desde este equipo"
                else "el servidor no pudo conectar con ${problem.target}"
            ForwardProblem.Kind.UNSUPPORTED_REQUEST ->
                "un cliente pidió algo que el proxy no admite (solo SOCKS 4/5 con CONNECT y sin autenticación)"
            ForwardProblem.Kind.OTHER -> "falló una conexión con ${problem.target}"
        }
    }
}
