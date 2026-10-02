package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.config.Tunnel
import io.github.danielperezmartinez.titanssh.config.TunnelType
import io.github.danielperezmartinez.titanssh.ssh.ForwardFailure
import io.github.danielperezmartinez.titanssh.ssh.ForwardProblem
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.PortForward
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshForward
import io.github.danielperezmartinez.titanssh.ssh.SshForwardFailed
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The tunnel lifecycle of a [SessionTab] ([[Ejecutar los túneles de las
 * sesiones]]) on fakes: opened on connect, reported when they fail without
 * ending the session, reopened after a micro-cut and closed with the tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabTunnelsTest {

    private class FakeShell : SshShell {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = ch.receiveAsFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { ch.close() }
        fun end() { ch.close() }
    }

    private class FakeForward(val forward: PortForward, val onProblem: (ForwardProblem) -> Unit) : SshForward {
        var closed = false
        override suspend fun close() { closed = true }
    }

    /** Opens forwards unless their listen port is in [refuse], the first [refusals] times. */
    private class FakeSession(
        private val refuse: Map<Int, ForwardFailure> = emptyMap(),
        private var refusals: Int = Int.MAX_VALUE,
    ) : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val shell = FakeShell()
        val forwards = mutableListOf<FakeForward>()
        override suspend fun openShell(columns: Int, rows: Int): SshShell = shell
        override suspend fun openForward(forward: PortForward, onProblem: (ForwardProblem) -> Unit): SshForward {
            refuse[forward.listenPort]?.let { if (refusals-- > 0) throw SshForwardFailed(it, "refused") }
            return FakeForward(forward, onProblem).also { forwards += it }
        }
        override suspend fun close() { _state.value = SshConnectionState.DISCONNECTED; shell.end() }
        fun drop() { _state.value = SshConnectionState.DISCONNECTED; shell.end() }
    }

    private class FakeConnector(sessions: List<FakeSession>) : SshConnector {
        private val plan = ArrayDeque(sessions)
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
            via: List<SshHop>,
        ): SshSession = plan.removeFirstOrNull() ?: throw SshConnectFailed("no more sessions")
    }

    private val local = Tunnel("t1", TunnelType.LOCAL, label = "db", listenPort = 15432, destinationHost = "db", destinationPort = 5432)
    private val socks = Tunnel("t2", TunnelType.DYNAMIC_SOCKS, listenPort = 1080)
    private val remote = Tunnel("t3", TunnelType.REMOTE, listenPort = 9000, destinationHost = "localhost", destinationPort = 3000)
    private val disabled = Tunnel("t4", TunnelType.LOCAL, enabled = false, listenPort = 1, destinationHost = "x", destinationPort = 1)

    private fun newTab(scope: CoroutineScope, connector: SshConnector, tunnels: List<Tunnel>): SessionTab {
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        val resolved = ResolvedConnection(
            session = Session(id = "s", name = "n", hostId = "h", tunnels = tunnels),
            host = host,
            endpoint = SshEndpoint("x", 22, "u"),
            auth = host.auth,
            appearance = TerminalAppearance(),
            jumps = emptyList(),
        )
        return SessionTab(
            id = "tab",
            resolved = resolved,
            connector = connector,
            credentials = { SshCredentials.Password("pw".toCharArray()) },
            knownHostsStore = InMemoryKnownHostsStore(),
            scope = scope,
            reconnect = ReconnectPolicy(maxAttempts = 3, initialBackoffMillis = 10, maxBackoffMillis = 20, dropGraceMillis = 100),
        )
    }

    private fun SessionTab.stateOf(id: String) = tunnels.value.first { it.tunnel.id == id }

    @Test
    fun enabled_tunnels_open_on_connect() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession()
        val tab = newTab(scope, FakeConnector(listOf(session)), listOf(local, socks, remote, disabled))

        assertEquals(listOf("t1", "t2", "t3"), tab.tunnels.value.map { it.tunnel.id }, "only enabled tunnels")
        assertTrue(tab.tunnels.value.all { it.state == TunnelState.WAITING })

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertTrue(tab.tunnels.value.all { it.state == TunnelState.ACTIVE })
        assertEquals(
            listOf(
                PortForward.Local("127.0.0.1", 15432, "db", 5432),
                PortForward.DynamicSocks("127.0.0.1", 1080),
                PortForward.Remote("127.0.0.1", 9000, "localhost", 3000),
            ),
            session.forwards.map { it.forward },
        )

        tab.close()
        advanceUntilIdle()
        assertTrue(session.forwards.all { it.closed }, "closing the tab closes its tunnels")
    }

    @Test
    fun a_failed_tunnel_is_reported_and_the_session_stays_up() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession(refuse = mapOf(15432 to ForwardFailure.PORT_IN_USE))
        val tab = newTab(scope, FakeConnector(listOf(session)), listOf(local, socks))

        tab.start()
        runCurrent() // not advanceUntilIdle: a taken port is retried for as long as the tab lives
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(TunnelState.FAILED, tab.stateOf("t1").state)
        assertEquals("el puerto 15432 ya está en uso en este equipo · se reintenta", tab.stateOf("t1").detail)
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t2").state)

        // A connection through an open tunnel that fails shows on it, still active.
        session.forwards.single().onProblem(ForwardProblem(ForwardProblem.Kind.UNREACHABLE, "10.0.0.9:22"))
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t2").state)
        assertEquals("el servidor no pudo conectar con 10.0.0.9:22", tab.stateOf("t2").detail)
        tab.close()
    }

    @Test
    fun a_tunnel_listening_on_the_network_opens_only_when_allowed() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession()
        val lan = socks.copy(id = "t5", listenHost = "0.0.0.0")
        val allowed = local.copy(id = "t6", listenHost = "192.168.1.20", allowFromNetwork = true)
        val tab = newTab(scope, FakeConnector(listOf(session)), listOf(lan, allowed))

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(TunnelState.FAILED, tab.stateOf("t5").state)
        assertEquals(
            "escucha en 0.0.0.0 sin permitir el acceso desde la red: edita el túnel",
            tab.stateOf("t5").detail,
        )
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t6").state)
        assertEquals(listOf(PortForward.Local("192.168.1.20", 15432, "db", 5432)), session.forwards.map { it.forward })
        tab.close()
    }

    @Test
    fun a_port_the_server_still_holds_is_retried_until_it_opens() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        // After a micro-cut the server keeps the old connection's port a while.
        val session = FakeSession(refuse = mapOf(9000 to ForwardFailure.REFUSED_BY_SERVER), refusals = 3)
        val tab = newTab(scope, FakeConnector(listOf(session)), listOf(remote))

        tab.start()
        runCurrent()
        assertEquals(TunnelState.FAILED, tab.stateOf("t3").state)

        advanceTimeBy(2 * SessionTunnels.FAST_RETRY_MILLIS + 1)
        assertEquals(TunnelState.FAILED, tab.stateOf("t3").state, "still refused on the 2nd and 3rd try")
        advanceTimeBy(SessionTunnels.FAST_RETRY_MILLIS)
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t3").state)
        assertEquals(null, tab.stateOf("t3").detail)

        advanceUntilIdle() // nothing left to retry: the loop has ended
        assertEquals(1, session.forwards.size)
        tab.close()
    }

    @Test
    fun tunnels_reopen_after_a_drop() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val first = FakeSession()
        val second = FakeSession()
        val tab = newTab(scope, FakeConnector(listOf(first, second)), listOf(local))

        tab.start()
        advanceUntilIdle()
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t1").state)

        first.drop()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertTrue(first.forwards.single().closed, "the old connection's tunnel is released")
        assertEquals(1, second.forwards.size, "reopened on the new connection")
        assertEquals(TunnelState.ACTIVE, tab.stateOf("t1").state)
        tab.close()
    }

    @Test
    fun a_tunnel_without_destination_fails_alone() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession()
        val broken = local.copy(destinationHost = null)
        val tab = newTab(scope, FakeConnector(listOf(session)), listOf(broken))

        tab.start()
        advanceUntilIdle()
        assertEquals(TunnelState.FAILED, tab.stateOf("t1").state)
        assertEquals("falta el destino", tab.stateOf("t1").detail)
        assertTrue(session.forwards.isEmpty())
        tab.close()
    }
}
