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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Start scripts on the level-3 path of [SessionTab]
 * ([[Scripts de inicio por sesión sobre el agente]]): the automation runs only
 * when the agent reports a fresh PTY, is told whether that PTY replaces one lost
 * during a drop, and sends its input as `INPUT` frames. Fakes stand in for SSH
 * and for `titan-agent`, driven on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabAgentAutomationTest {

    /** Stands in for `titan-agent`: answers HELLO with [created] and a prompt. */
    private class FakeAgent(private val created: Boolean) : SshExecChannel {
        private val outCh = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = outCh.receiveAsFlow()
        override val errors: Flow<ByteArray> = emptyFlow()
        private val decoder = AgentProtocol.FrameDecoder()
        val inputs = mutableListOf<String>()

        override suspend fun send(data: ByteArray) {
            for (frame in decoder.feed(data)) when (frame) {
                is AgentFrame.Hello -> {
                    outCh.trySend(AgentProtocol.encode(AgentFrame.HelloOk(0, 0, created)))
                    outCh.trySend(AgentProtocol.encode(AgentFrame.Data(0, "$ ".encodeToByteArray())))
                }
                is AgentFrame.Input -> inputs += frame.bytes.decodeToString()
                else -> Unit
            }
        }

        override suspend fun close(): Int? { outCh.close(); return 0 }
        fun end() { outCh.close() }
    }

    private class FakeSession(created: Boolean) : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val agent = FakeAgent(created)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("agent path only")
        override suspend fun exec(command: String): SshExecChannel =
            if (command == ShellSyntax.PROBE) FakeProbeChannel() else agent
        override suspend fun close() { _state.value = SshConnectionState.DISCONNECTED; agent.end() }
        /** Network micro-cut: the agent channel ends and the transport reports itself down. */
        fun drop() { _state.value = SshConnectionState.DISCONNECTED; agent.end() }
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

    /** Records each hook call and, like the real one, sends after the prompt. */
    private class RecordingAutomation : ShellAutomation {
        val calls = mutableListOf<String>()
        override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) { calls += "shell" }
        override suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) { calls += "reconnected" }
        override suspend fun onAgentSessionCreated(io: ShellIo, resolved: ResolvedConnection, afterDrop: Boolean) {
            calls += "agent(afterDrop=$afterDrop)"
            io.output.first { it.isNotEmpty() }
            io.send("cd /work\n")
        }
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

    private fun newTab(scope: CoroutineScope, sessions: List<FakeSession>, automation: ShellAutomation) = SessionTab(
        id = "tab",
        resolved = resolved(),
        connector = FakeConnector(sessions),
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = InMemoryKnownHostsStore(),
        scope = scope,
        automation = automation,
        reconnect = ReconnectPolicy(maxAttempts = 3, initialBackoffMillis = 10, maxBackoffMillis = 20, dropGraceMillis = 100),
        agentDeployer = { AgentDeployment.Ready(AgentLaunch("agent", RemoteShell.POSIX)) },
    )

    @Test
    fun a_fresh_agent_session_runs_the_start_chain_over_input_frames() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession(created = true)
        val automation = RecordingAutomation()
        val tab = newTab(scope, listOf(session), automation)

        tab.start()
        advanceUntilIdle()

        assertEquals(listOf("agent(afterDrop=false)"), automation.calls)
        assertEquals(listOf("cd /work\n"), session.agent.inputs)
        tab.close()
    }

    @Test
    fun reattaching_to_a_live_agent_session_runs_nothing() = runTest {
        // E.g. the app was closed and reopened: the PTY kept running on the host.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val session = FakeSession(created = false)
        val automation = RecordingAutomation()
        val tab = newTab(scope, listOf(session), automation)

        tab.start()
        advanceUntilIdle()

        assertTrue(automation.calls.isEmpty(), "got ${automation.calls}")
        assertTrue(session.agent.inputs.isEmpty())
        tab.close()
    }

    @Test
    fun after_a_drop_only_a_lost_agent_session_is_rebuilt() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val first = FakeSession(created = true)
        val reattached = FakeSession(created = false) // the agent kept the PTY
        val recreated = FakeSession(created = true) // the host lost it (reboot)
        val automation = RecordingAutomation()
        val tab = newTab(scope, listOf(first, reattached, recreated), automation)

        tab.start()
        advanceUntilIdle()
        first.drop()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(listOf("agent(afterDrop=false)"), automation.calls, "a re-attach replays nothing")

        reattached.drop()
        advanceUntilIdle()
        assertEquals(listOf("agent(afterDrop=false)", "agent(afterDrop=true)"), automation.calls)
        assertEquals(listOf("cd /work\n"), recreated.agent.inputs)
        tab.close()
    }
}
