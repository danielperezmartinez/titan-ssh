package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * [SessionTab] when level 3 is unavailable ([[Diagnóstico cuando el nivel 3 no
 * está disponible]]): it never degrades in silence. The reason lands in
 * [SessionTab.resilience], the same connection goes on as a shell (level 2/1),
 * and later reconnects of the tab do not retry the agent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabAgentDegradeTest {

    private class FakeShell : SshShell {
        private val out = Channel<ByteArray>(Channel.UNLIMITED).apply { trySend("$ ".encodeToByteArray()) }
        override val output: Flow<ByteArray> = out.receiveAsFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { out.close() }
        fun end() { out.close() }
    }

    /** An agent front that exits at once with [stderr] and [exitStatus]. */
    private class FailingFront(private val stderr: String, private val exitStatus: Int) : SshExecChannel {
        override val output: Flow<ByteArray> = emptyFlow()
        override val errors: Flow<ByteArray> = flowOf(stderr.encodeToByteArray())
        override suspend fun send(data: ByteArray) {}
        override suspend fun close(): Int = exitStatus
    }

    private class FakeSession(private val front: SshExecChannel?) : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val execs = mutableListOf<String>()
        val shells = mutableListOf<FakeShell>()
        override suspend fun openShell(columns: Int, rows: Int): SshShell = FakeShell().also { shells += it }
        override suspend fun exec(command: String): SshExecChannel {
            execs += command
            return front ?: error("no exec expected")
        }
        override suspend fun close() { _state.value = SshConnectionState.DISCONNECTED; shells.forEach { it.end() } }
        fun drop() { _state.value = SshConnectionState.DISCONNECTED; shells.forEach { it.end() } }
    }

    private class FakeConnector(sessions: List<FakeSession>) : SshConnector {
        private val plan = ArrayDeque(sessions)
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
        ): SshSession = plan.removeFirstOrNull() ?: throw SshConnectFailed("no more sessions")
    }

    /** Reports what a StartScriptAutomation would find on the shell path. */
    private class MultiplexerAutomation(private val kind: TerminalMultiplexer.Kind) : ShellAutomation {
        override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) = io.reportMultiplexer(kind)
        override suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) = io.reportMultiplexer(kind)
    }

    private fun resolved(): ResolvedConnection {
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        return ResolvedConnection(
            session = Session(id = "s", name = "n", hostId = "h", resilienceLevel = ResilienceLevel.AGENT),
            host = host,
            endpoint = SshEndpoint("x", 22, "u"),
            auth = host.auth,
            appearance = TerminalAppearance(),
            proxyJump = null,
        )
    }

    private fun newTab(
        scope: CoroutineScope,
        sessions: List<FakeSession>,
        deployer: AgentDeployer,
        kind: TerminalMultiplexer.Kind = TerminalMultiplexer.Kind.TMUX,
    ) = SessionTab(
        id = "tab",
        resolved = resolved(),
        connector = FakeConnector(sessions),
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = InMemoryKnownHostsStore(),
        scope = scope,
        automation = MultiplexerAutomation(kind),
        reconnect = ReconnectPolicy(maxAttempts = 3, initialBackoffMillis = 10, maxBackoffMillis = 20, dropGraceMillis = 100),
        agentDeployer = deployer,
    )

    @Test
    fun an_install_that_cannot_run_level_3_says_why_and_opens_a_shell() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val first = FakeSession(front = null)
        val second = FakeSession(front = null)
        var installs = 0
        val deployer = AgentDeployer {
            installs++
            AgentDeployment.Unavailable(AgentIssue(AgentDiagnostics.E_UNSUPPORTED_TARGET, "SunOS"))
        }
        val tab = newTab(scope, listOf(first, second), deployer)

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(AgentDiagnostics.E_UNSUPPORTED_TARGET, tab.resilience.value.issue?.code)
        assertEquals(EffectiveLevel.MULTIPLEXER, tab.resilience.value.level)
        assertEquals(TerminalMultiplexer.Kind.TMUX, tab.resilience.value.multiplexer)
        assertEquals(1, first.shells.size)

        // A micro-cut: the reconnect goes straight to the shell, no new install.
        first.drop()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(1, installs)
        assertEquals(1, second.shells.size)
        assertEquals(AgentDiagnostics.E_UNSUPPORTED_TARGET, tab.resilience.value.issue?.code)
        tab.close()
    }

    @Test
    fun an_agent_that_refuses_degrades_on_the_same_connection() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val front = FailingFront("TITAN_AGENT_ERROR E_STATE_DIR state dir is group-writable\n", 1)
        val session = FakeSession(front)
        val deployer = AgentDeployer { AgentDeployment.Ready(AgentLaunch("agent", RemoteShell.POSIX)) }
        val tab = newTab(scope, listOf(session), deployer, TerminalMultiplexer.Kind.NONE)

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(AgentIssue("E_STATE_DIR", "state dir is group-writable"), tab.resilience.value.issue)
        assertEquals(EffectiveLevel.BASE, tab.resilience.value.level)
        assertEquals(1, session.execs.size)
        assertEquals(1, session.shells.size)
        tab.close()
    }

    @Test
    fun a_warning_keeps_level_3() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val agent = object : SshExecChannel {
            private val out = Channel<ByteArray>(Channel.UNLIMITED)
            override val output: Flow<ByteArray> = out.receiveAsFlow()
            override val errors: Flow<ByteArray> = emptyFlow()
            override suspend fun send(data: ByteArray) {
                out.trySend(AgentProtocol.encode(AgentFrame.HelloOk(0, 0, created = false)))
            }
            override suspend fun close(): Int? { out.close(); return 0 }
        }
        val session = FakeSession(agent)
        val warning = AgentIssue(AgentDiagnostics.E_SYSTEMD_KILL)
        val deployer = AgentDeployer { AgentDeployment.Ready(AgentLaunch("agent", RemoteShell.POSIX), warning) }
        val tab = newTab(scope, listOf(session), deployer)

        tab.start()
        advanceUntilIdle()
        assertEquals(EffectiveLevel.AGENT, tab.resilience.value.level)
        assertEquals(warning, tab.resilience.value.issue)
        assertNull(tab.resilience.value.multiplexer)
        assertEquals(0, session.shells.size)
        tab.close()
    }
}
