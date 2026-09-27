package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Headless coverage of resilience level 1 in [SessionTab]
 * ([[Resiliencia de sesión ante microcortes de red]]): a drop is ridden out and
 * the scrollback preserved, a clean exit is not, and retries are bounded by
 * time; "reconectar" and the network coming back cut the wait short
 * ([[Reconexión que no se rinde tras un corte largo]]). Uses
 * fakes for the SSH engine so the reconnect lifecycle is driven on virtual time
 * without a network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabReconnectTest {

    /** A shell whose output is a channel the test closes to simulate the stream ending. */
    private class FakeShell : SshShell {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = ch.receiveAsFlow()
        val sent = mutableListOf<String>()
        override suspend fun send(data: ByteArray) { sent += data.decodeToString() }
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { ch.close() }
        fun emit(text: String) { ch.trySend(text.encodeToByteArray()) }
        fun end() { ch.close() }
    }

    /** A session whose transport state the test flips to model a drop vs a clean exit. */
    private class FakeSession : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val shell = FakeShell()
        override suspend fun openShell(columns: Int, rows: Int): SshShell = shell
        override suspend fun close() { _state.value = SshConnectionState.DISCONNECTED; shell.end() }
        /** Network micro-cut: the stream ends and the transport reports itself down. */
        fun drop() { _state.value = SshConnectionState.DISCONNECTED; shell.end() }
        /** User exit: the shell ends but the transport stays up. */
        fun cleanExit() { shell.end() }
    }

    /**
     * Hands out a scripted sequence of sessions (or connection failures) per
     * connect. While [down] every attempt fails without using the plan.
     */
    private class FakeConnector(sessions: List<Result<FakeSession>>) : SshConnector {
        private val plan = ArrayDeque(sessions)
        var calls = 0
        var down = false
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
        ): SshSession {
            calls++
            if (down) throw SshConnectFailed("network down")
            val next = plan.removeFirstOrNull() ?: throw SshConnectFailed("no more sessions")
            return next.getOrElse { throw it }
        }
    }

    private fun resolved(): ResolvedConnection {
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        return ResolvedConnection(
            session = Session(id = "s", name = "n", hostId = "h"),
            host = host,
            endpoint = SshEndpoint("x", 22, "u"),
            auth = host.auth,
            appearance = TerminalAppearance(),
            proxyJump = null,
        )
    }

    private val fastPolicy = ReconnectPolicy(
        maxAttempts = 3,
        initialBackoffMillis = 10,
        maxBackoffMillis = 20,
        dropGraceMillis = 100,
    )

    /** No attempt limit: only the 5 s budget ends the retries. */
    private val budgetPolicy = ReconnectPolicy(
        giveUpAfterMillis = 5_000,
        initialBackoffMillis = 10,
        maxBackoffMillis = 1_000,
        dropGraceMillis = 100,
    )

    private fun TestScope.newTab(
        scope: CoroutineScope,
        connector: FakeConnector,
        policy: ReconnectPolicy = fastPolicy,
    ) = SessionTab(
        id = "tab",
        resolved = resolved(),
        connector = connector,
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = InMemoryKnownHostsStore(),
        scope = scope,
        reconnect = policy,
        timeSource = testScheduler.timeSource,
    )

    private fun SessionTab.text(): String {
        val snap = snapshot.value
        return (snap.scrollback + snap.screen).joinToString("\n") { line ->
            line.joinToString("") { it.char.toString() }
        }
    }

    @Test
    fun reconnects_after_a_drop_and_preserves_scrollback() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val s2 = FakeSession()
        val connector = FakeConnector(listOf(Result.success(s1), Result.success(s2)))
        val tab = newTab(scope, connector)

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)

        s1.shell.emit("hello-before\n")
        advanceUntilIdle()
        assertTrue(tab.text().contains("hello-before"), "first-session output is painted")

        // Micro-cut: stream ends and transport drops. The tab must ride it out.
        s1.drop()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls, "it reconnected exactly once")
        assertTrue(tab.text().contains("hello-before"), "scrollback survives the reconnect")

        s2.shell.emit("world-after\n")
        advanceUntilIdle()
        val text = tab.text()
        assertTrue(text.contains("hello-before") && text.contains("world-after"), "both sessions' output is in one continuous buffer")

        tab.close()
    }

    @Test
    fun a_clean_exit_does_not_reconnect() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val connector = FakeConnector(listOf(Result.success(s1)))
        val tab = newTab(scope, connector)

        tab.start()
        advanceUntilIdle()
        s1.shell.emit("bye\n")
        s1.cleanExit()
        advanceUntilIdle()

        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)
        assertEquals("Sesión finalizada", tab.status.value.detail)
        assertEquals(1, connector.calls, "a clean exit is not a micro-cut: no reconnect")

        tab.close()
    }

    @Test
    fun gives_up_after_max_attempts() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val connector = FakeConnector(
            listOf(
                Result.success(s1),
                Result.failure(SshConnectFailed("down")),
                Result.failure(SshConnectFailed("down")),
            ),
        )
        val tab = newTab(scope, connector)

        tab.start()
        advanceUntilIdle()
        s1.drop()
        advanceUntilIdle()

        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)
        assertTrue(
            tab.status.value.detail?.contains("No se pudo reconectar") == true,
            "it reports giving up: ${tab.status.value.detail}",
        )
        // 1 initial + maxAttempts reconnects.
        assertEquals(1 + fastPolicy.maxAttempts!!, connector.calls)

        tab.close()
    }

    @Test
    fun keeps_retrying_well_past_six_attempts_within_the_budget() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val s2 = FakeSession()
        val failures = List(12) { Result.failure<FakeSession>(SshConnectFailed("down")) }
        val connector = FakeConnector(listOf(Result.success(s1)) + failures + Result.success(s2))
        val tab = newTab(scope, connector, budgetPolicy.copy(giveUpAfterMillis = 60_000))

        tab.start()
        advanceUntilIdle()
        s1.drop()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(1 + 12 + 1, connector.calls, "it rode out twelve failed attempts")

        tab.close()
    }

    @Test
    fun gives_up_once_the_time_budget_is_spent() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val connector = FakeConnector(listOf(Result.success(s1)))
        val tab = newTab(scope, connector, budgetPolicy)

        tab.start()
        advanceUntilIdle()
        val droppedAt = testScheduler.currentTime
        connector.down = true
        s1.drop()
        advanceUntilIdle()

        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)
        assertEquals("No se pudo reconectar en 5 s", tab.status.value.detail)
        assertTrue(testScheduler.currentTime - droppedAt >= 5_000, "it kept trying for the whole budget")

        tab.close()
    }

    @Test
    fun reconnect_now_cuts_the_backoff_short() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val s2 = FakeSession()
        val connector = FakeConnector(listOf(Result.success(s1), Result.success(s2)))
        val slow = ReconnectPolicy(initialBackoffMillis = 60_000, maxBackoffMillis = 60_000, dropGraceMillis = 100)
        val tab = newTab(scope, connector, slow)

        tab.start()
        advanceUntilIdle()
        s1.drop()
        advanceTimeBy(1_000)
        assertEquals(TabPhase.RECONNECTING, tab.status.value.phase)
        assertEquals(1, connector.calls, "still waiting out the backoff")

        tab.reconnectNow()
        advanceTimeBy(1_000)
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls)

        tab.close()
    }

    @Test
    fun reconnect_now_brings_back_a_tab_that_gave_up_with_its_scrollback() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val s2 = FakeSession()
        val connector = FakeConnector(listOf(Result.success(s1), Result.success(s2)))
        val tab = newTab(scope, connector, budgetPolicy)

        tab.start()
        advanceUntilIdle()
        s1.shell.emit("before-the-cut\n")
        advanceUntilIdle()
        connector.down = true
        s1.drop()
        advanceUntilIdle()
        assertEquals(TabPhase.DISCONNECTED, tab.status.value.phase)

        connector.down = false
        tab.reconnectNow()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertTrue(tab.text().contains("before-the-cut"), "the same emulator carries on")

        tab.close()
    }

    @Test
    fun reconnect_now_retries_a_failed_first_connection() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val connector = FakeConnector(listOf(Result.failure(SshConnectFailed("unreachable")), Result.success(s1)))
        val tab = newTab(scope, connector)

        tab.start()
        advanceUntilIdle()
        assertEquals(TabPhase.FAILED, tab.status.value.phase)

        tab.reconnectNow()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls)

        tab.close()
    }

    @Test
    fun the_network_coming_back_retries_only_tabs_that_were_cut() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

        // Gave up after a cut: the network coming back revives it.
        val first = FakeSession()
        val cut = FakeConnector(listOf(Result.success(first), Result.success(FakeSession())))
        val gaveUp = newTab(scope, cut, budgetPolicy)
        gaveUp.start()
        advanceUntilIdle()
        cut.down = true
        first.drop()
        advanceUntilIdle()
        assertEquals(TabPhase.DISCONNECTED, gaveUp.status.value.phase)

        // Ended by the user, and failed on its very first connection: left alone.
        val exiting = FakeSession()
        val exited = FakeConnector(listOf(Result.success(exiting)))
        val ended = newTab(scope, exited)
        ended.start()
        advanceUntilIdle()
        exiting.cleanExit()
        advanceUntilIdle()
        val unreachable = FakeConnector(listOf(Result.failure(SshConnectFailed("unreachable"))))
        val failed = newTab(scope, unreachable)
        failed.start()
        advanceUntilIdle()

        cut.down = false
        listOf(gaveUp, ended, failed).forEach { it.onNetworkRestored() }
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, gaveUp.status.value.phase)
        assertEquals(TabPhase.DISCONNECTED, ended.status.value.phase)
        assertEquals(1, exited.calls)
        assertEquals(TabPhase.FAILED, failed.status.value.phase)
        assertEquals(1, unreachable.calls)

        listOf(gaveUp, ended, failed).forEach { it.close() }
    }

    @Test
    fun a_first_connection_failure_is_fatal_and_not_retried() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val connector = FakeConnector(listOf(Result.failure(SshConnectFailed("unreachable"))))
        val tab = newTab(scope, connector)

        tab.start()
        advanceUntilIdle()

        assertEquals(TabPhase.FAILED, tab.status.value.phase)
        assertEquals(1, connector.calls, "the very first connection is not auto-retried")

        tab.close()
    }
}
