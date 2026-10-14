package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Coverage of ADR-0023 in [SessionTab] and [BackgroundConnections]: which tabs
 * need their connection in the background, pausing and resuming a tab, no
 * attempts while the platform blocks the network, and the pause after a while
 * in the background when no tab needs the connection. Fakes stand in for the
 * SSH engine, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabBackgroundTest {

    private class FakeShell : SshShell {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = ch.receiveAsFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { ch.close() }
        fun emit(text: String) { ch.trySend(text.encodeToByteArray()) }
        fun end() { ch.close() }
    }

    private class FakeSession : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val shell = FakeShell()
        var closed = false
        override suspend fun openShell(columns: Int, rows: Int): SshShell = shell
        override suspend fun close() { closed = true; _state.value = SshConnectionState.DISCONNECTED; shell.end() }
        fun drop() { _state.value = SshConnectionState.DISCONNECTED; shell.end() }
    }

    /** A new [FakeSession] per connect, kept in [sessions]; fails while [down]. */
    private class FakeConnector : SshConnector {
        val sessions = mutableListOf<FakeSession>()
        val calls: Int get() = attempts
        private var attempts = 0
        var down = false
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
            via: List<SshHop>,
        ): SshSession {
            attempts++
            if (down) throw SshConnectFailed("network down")
            return FakeSession().also { sessions += it }
        }
    }

    /** Reports tmux on every shell, so the tab runs at level 2. */
    private object FoundTmux : ShellAutomation {
        override suspend fun onShellReady(io: ShellIo, resolved: ResolvedConnection) =
            io.reportMultiplexer(TerminalMultiplexer.Kind.TMUX)

        override suspend fun onReconnected(io: ShellIo, resolved: ResolvedConnection) =
            io.reportMultiplexer(TerminalMultiplexer.Kind.TMUX)
    }

    private fun resolved(level: ResilienceLevel = ResilienceLevel.BASE): ResolvedConnection {
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        return ResolvedConnection(
            session = Session(id = "s", name = "n", hostId = "h", resilienceLevel = level),
            host = host,
            endpoint = SshEndpoint("x", 22, "u"),
            auth = host.auth,
            appearance = TerminalAppearance(),
            jumps = emptyList(),
        )
    }

    private val policy = ReconnectPolicy(
        giveUpAfterMillis = 5_000,
        initialBackoffMillis = 10,
        maxBackoffMillis = 1_000,
        dropGraceMillis = 100,
    )

    private fun TestScope.newTab(
        scope: CoroutineScope,
        connector: FakeConnector,
        level: ResilienceLevel = ResilienceLevel.BASE,
        automation: ShellAutomation = ShellAutomation.None,
        blocked: MutableStateFlow<Boolean> = MutableStateFlow(false),
        id: String = "tab",
    ) = SessionTab(
        id = id,
        resolved = resolved(level),
        connector = connector,
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = InMemoryKnownHostsStore(),
        scope = scope,
        automation = automation,
        reconnect = policy,
        timeSource = testScheduler.timeSource,
        networkBlocked = blocked,
    )

    private fun SessionTab.text(): String {
        val snap = snapshot.value
        return (snap.scrollback + snap.screen).joinToString("\n") { line -> line.joinToString("") { it.char.toString() } }
    }

    private fun need(
        type: SessionType = SessionType.TERMINAL,
        phase: TabPhase = TabPhase.CONNECTED,
        level: EffectiveLevel? = EffectiveLevel.BASE,
        expectsAgent: Boolean = false,
        hasTunnels: Boolean = false,
        downloading: Boolean = false,
    ) = needsConnection(type, phase, level, expectsAgent, hasTunnels, downloading)

    @Test
    fun only_tabs_that_would_lose_something_need_their_connection() {
        assertTrue(need(level = EffectiveLevel.BASE), "level 1 loses its shell")
        assertTrue(need(phase = TabPhase.RECONNECTING), "a level-1 tab coming back still needs it")
        assertTrue(need(level = null), "level not known yet, not waiting for the agent")
        assertFalse(need(level = null, expectsAgent = true), "about to attach to the agent")
        assertFalse(need(level = EffectiveLevel.MULTIPLEXER), "tmux/screen keep the PTY")
        assertFalse(need(level = EffectiveLevel.AGENT), "the agent keeps the PTY")
        assertTrue(need(level = EffectiveLevel.AGENT, hasTunnels = true), "other apps may use its tunnels")
        assertFalse(need(type = SessionType.MOUSEPAD, level = null), "a mouse pad has nothing to keep")
        assertFalse(need(type = SessionType.SFTP, level = null), "an idle file browser")
        assertTrue(need(type = SessionType.SFTP, level = null, downloading = true), "a download in progress")
        for (phase in listOf(TabPhase.CONNECTING, TabPhase.PAUSED, TabPhase.DISCONNECTED, TabPhase.FAILED)) {
            assertFalse(need(phase = phase), "no live connection to keep while $phase")
        }
    }

    @Test
    fun pause_closes_the_connection_and_resume_reconnects_with_the_scrollback() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val tab = newTab(scope, connector)
        tab.start()
        advanceUntilIdle()
        connector.sessions[0].shell.emit("before-pause\n")
        advanceUntilIdle()

        tab.pause()
        advanceUntilIdle()
        assertEquals(TabPhase.PAUSED, tab.status.value.phase)
        assertTrue(connector.sessions[0].closed, "the connection is closed, not left to time out")

        advanceTimeBy(60 * 60_000L)
        assertEquals(1, connector.calls, "no attempt while paused, however long")
        assertEquals(TabPhase.PAUSED, tab.status.value.phase, "a pause never gives up")

        tab.resume()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls, "it connects again at once")
        assertTrue(tab.text().contains("before-pause"), "the screen survives the pause")
        tab.close()
        scope.cancel()
    }

    @Test
    fun a_pause_during_the_backoff_stops_the_retries() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val tab = newTab(scope, connector)
        tab.start()
        advanceUntilIdle()
        connector.down = true
        connector.sessions[0].drop()
        advanceTimeBy(500)
        assertEquals(TabPhase.RECONNECTING, tab.status.value.phase)

        tab.pause()
        runCurrent()
        val before = connector.calls
        advanceTimeBy(30 * 60_000L)
        assertEquals(TabPhase.PAUSED, tab.status.value.phase)
        assertEquals(before, connector.calls, "no attempt while paused")

        connector.down = false
        tab.resume()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        tab.close()
        scope.cancel()
    }

    @Test
    fun pausing_a_tab_that_ended_does_nothing() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val tab = newTab(scope, connector)
        tab.start()
        advanceUntilIdle()
        connector.sessions[0].shell.end() // the user exited
        advanceUntilIdle()
        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)

        tab.pause()
        advanceUntilIdle()
        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)
        tab.resume()
        advanceUntilIdle()
        assertEquals(1, connector.calls)
        tab.close()
        scope.cancel()
    }

    @Test
    fun no_attempts_while_the_network_is_blocked_and_that_time_is_not_counted() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val blocked = MutableStateFlow(false)
        val tab = newTab(scope, connector, blocked = blocked)
        tab.start()
        advanceUntilIdle()

        // The screen locks: the app's network is blocked and the connection drops.
        blocked.value = true
        connector.down = true
        connector.sessions[0].drop()
        advanceTimeBy(60 * 60_000L)
        assertEquals(1, connector.calls, "no attempt can get out, so none is made")
        assertEquals(TabPhase.RECONNECTING, tab.status.value.phase, "it waits rather than giving up")

        // Unblocked with the host still unreachable: the 5 s budget starts now.
        blocked.value = false
        advanceTimeBy(2_000)
        assertTrue(connector.calls > 1, "it tries as soon as the network is unblocked")
        assertEquals(TabPhase.RECONNECTING, tab.status.value.phase, "the blocked hour did not use up the budget")
        advanceUntilIdle()
        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase, "the budget still ends the retries")
        tab.close()
        scope.cancel()
    }

    @Test
    fun a_level_1_tab_keeps_every_tab_connected_in_the_background() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val tabs = MutableStateFlow(
            listOf(
                newTab(scope, connector, id = "base"),
                newTab(scope, connector, ResilienceLevel.AUTO_MULTIPLEXER, FoundTmux, id = "tmux"),
            ),
        )
        tabs.value.forEach { it.start() }
        val background = BackgroundConnections(scope, tabs, pauseAfterMillis = 60_000)
        advanceUntilIdle()
        assertEquals(KeepAliveRequest(needed = true, connectedTabs = 2), background.request.value)

        background.setForeground(false)
        advanceTimeBy(10 * 60_000L)
        assertTrue(tabs.value.all { it.status.value.phase == TabPhase.CONNECTED }, "the service keeps them all")
        assertEquals(2, connector.calls)

        tabs.value.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun with_nothing_to_lose_tabs_pause_after_a_minute_and_resume_on_return() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector()
        val tab = newTab(scope, connector, ResilienceLevel.AUTO_MULTIPLEXER, FoundTmux)
        val tabs = MutableStateFlow(listOf(tab))
        tab.start()
        val background = BackgroundConnections(scope, tabs, pauseAfterMillis = 60_000)
        advanceUntilIdle()
        assertEquals(KeepAliveRequest(needed = false, connectedTabs = 1), background.request.value)

        // A short trip to another app cuts nothing.
        background.setForeground(false)
        advanceTimeBy(30_000)
        background.setForeground(true)
        advanceTimeBy(5 * 60_000L)
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)

        background.setForeground(false)
        advanceTimeBy(59_000)
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase, "not before the minute")
        advanceTimeBy(2_000)
        assertEquals(TabPhase.PAUSED, tab.status.value.phase)
        assertTrue(connector.sessions[0].closed)

        advanceTimeBy(60 * 60_000L)
        assertEquals(1, connector.calls, "nothing runs while paused")

        background.setForeground(true)
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls)
        tab.close()
        scope.cancel()
    }
}
