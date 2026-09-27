package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHostKeyRejected
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A host key the user trusts after the SSH handshake has timed out still gets
 * the tab connected ([[Conectar tras confirmar tarde la clave del servidor]]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabHostKeyTest {

    private class FakeShell : SshShell {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = ch.receiveAsFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { ch.close() }
    }

    private class FakeSession : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        override suspend fun openShell(columns: Int, rows: Int): SshShell = FakeShell()
        override suspend fun close() { _state.value = SshConnectionState.DISCONNECTED }
    }

    /**
     * Behaves like sshj: the host key is checked on the transport's own thread
     * while the handshake waits at most [HANDSHAKE_MILLIS]. On timeout sshj
     * interrupts that thread ([interruptVerifier]).
     */
    private class HandshakeConnector(
        private val scope: CoroutineScope,
        private val interruptVerifier: Boolean,
    ) : SshConnector {
        var calls = 0
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
        ): SshSession {
            calls++
            val check = scope.async { hostKeyVerifier.verify(KEY) }
            val trusted = withTimeoutOrNull(HANDSHAKE_MILLIS) { check.await() }
            if (trusted == null) {
                if (interruptVerifier) check.cancel()
                throw SshConnectFailed("Could not connect to x:22")
            }
            if (!trusted) throw SshHostKeyRejected("rejected")
            return FakeSession()
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

    private fun newTab(scope: CoroutineScope, connector: SshConnector, store: InMemoryKnownHostsStore) = SessionTab(
        id = "tab",
        resolved = resolved(),
        connector = connector,
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = store,
        scope = scope,
    )

    private fun acceptsLate(interruptVerifier: Boolean) = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore()
        val connector = HandshakeConnector(scope, interruptVerifier)
        val tab = newTab(scope, connector, store)

        tab.start()
        advanceTimeBy(HANDSHAKE_MILLIS + 5_000)
        val prompt = assertNotNull(tab.pendingHostKey.value, "the prompt outlives the timed-out handshake")
        assertEquals(TabPhase.CONNECTING, tab.status.value.phase)
        assertEquals(1, connector.calls)

        prompt.accept()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(2, connector.calls, "it connected again on its own")
        assertNull(tab.pendingHostKey.value, "the key is known now: no second prompt")
        assertEquals(1, store.entriesFor("x", 22).size, "the key is saved once")

        tab.close()
    }

    @Test
    fun accepting_after_the_handshake_timed_out_connects_again() = acceptsLate(interruptVerifier = true)

    @Test
    fun accepting_late_works_when_the_verifier_is_not_interrupted() = acceptsLate(interruptVerifier = false)

    @Test
    fun rejecting_after_the_timeout_says_the_key_was_rejected() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore()
        val connector = HandshakeConnector(scope, interruptVerifier = true)
        val tab = newTab(scope, connector, store)

        tab.start()
        advanceTimeBy(HANDSHAKE_MILLIS + 5_000)
        assertNotNull(tab.pendingHostKey.value).reject()
        advanceUntilIdle()

        assertEquals(TabPhase.FAILED, tab.status.value.phase)
        assertEquals("Clave de host rechazada", tab.status.value.detail)
        assertEquals(1, connector.calls)
        assertEquals(0, store.entriesFor("x", 22).size)

        tab.close()
    }

    @Test
    fun accepting_in_time_connects_once() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore()
        val connector = HandshakeConnector(scope, interruptVerifier = true)
        val tab = newTab(scope, connector, store)

        tab.start()
        advanceTimeBy(1_000)
        assertNotNull(tab.pendingHostKey.value).accept()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(1, connector.calls)
        assertEquals(1, store.entriesFor("x", 22).size)

        tab.close()
    }

    @Test
    fun closing_the_tab_answers_an_open_prompt() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore()
        val tab = newTab(scope, HandshakeConnector(scope, interruptVerifier = false), store)

        tab.start()
        advanceTimeBy(HANDSHAKE_MILLIS + 5_000)
        assertNotNull(tab.pendingHostKey.value)

        tab.close()
        advanceUntilIdle()
        assertNull(tab.pendingHostKey.value, "the prompt is rejected, so nothing waits on it")
        assertEquals(0, store.entriesFor("x", 22).size)
    }

    private companion object {
        const val HANDSHAKE_MILLIS = 30_000L
        val KEY = HostKeyInfo(
            host = "x",
            port = 22,
            keyType = "ssh-ed25519",
            fingerprintSha256 = "SHA256:test",
            publicKeyBase64 = "AAAAC3NzaC1lZDI1NTE5AAAAItest",
        )
    }
}
