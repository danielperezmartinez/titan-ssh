package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.HostKeyPolicy
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.ssh.HostKeyInfo
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostEntry
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** A tab whose host has a ProxyJump goes through its jump hosts. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabJumpHostTest {

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

    /** Checks each host's key in order, hops first, like sshj does on the way in. */
    private class ChainConnector : SshConnector {
        val hops = mutableListOf<List<SshEndpoint>>()
        val passwords = mutableListOf<List<String>>()

        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
            via: List<SshHop>,
        ): SshSession {
            hops += via.map { it.endpoint }
            passwords += (via.map { it.credentials } + credentials).map { String((it as SshCredentials.Password).password) }
            for (hop in via) {
                if (!hop.hostKeyVerifier.verify(keyOf(hop.endpoint.host))) throw SshHostKeyRejected("rejected")
            }
            if (!hostKeyVerifier.verify(keyOf(endpoint.host))) throw SshHostKeyRejected("rejected")
            return FakeSession()
        }
    }

    private fun host(id: String, proxy: String? = null, policy: HostKeyPolicy = HostKeyPolicy.TOFU) = Host(
        id = id, alias = id, hostname = id, port = 22, username = "u-$id",
        auth = HostAuth.Password("pw-$id"), hostKeyPolicy = policy, proxyJumpHostId = proxy,
    )

    private fun newTab(scope: CoroutineScope, connector: SshConnector, store: InMemoryKnownHostsStore, vararg hosts: Host): SessionTab {
        val session = Session(id = "s", name = "n", hostId = "dest")
        val config = TitanConfig(hosts = hosts.toList(), sessions = listOf(session))
        return SessionTab(
            id = "tab",
            resolved = config.resolve(session),
            connector = connector,
            credentials = { auth -> SshCredentials.Password((auth as HostAuth.Password).secretRef.toCharArray()) },
            knownHostsStore = store,
            scope = scope,
        )
    }

    @Test
    fun connects_through_each_jump_host_with_its_own_credentials() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore(listOf(known("outer"), known("inner"), known("dest")))
        val connector = ChainConnector()
        val tab = newTab(scope, connector, store, host("dest", proxy = "inner"), host("inner", proxy = "outer"), host("outer"))

        tab.start()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(listOf(listOf(SshEndpoint("outer", 22, "u-outer"), SshEndpoint("inner", 22, "u-inner"))), connector.hops)
        assertEquals(listOf(listOf("pw-outer", "pw-inner", "pw-dest")), connector.passwords)
        tab.close()
    }

    @Test
    fun a_jump_host_key_follows_its_own_policy() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore(listOf(known("dest")))
        val tab = newTab(scope, ChainConnector(), store, host("dest", proxy = "b"), host("b", policy = HostKeyPolicy.STRICT))

        tab.start()
        advanceUntilIdle()

        assertNull(tab.pendingHostKey.value, "STRICT never asks")
        assertEquals(TabPhase.FAILED, tab.status.value.phase)
        assertEquals(
            "Bastión b: Host sin clave de confianza: la política STRICT no acepta claves nuevas",
            tab.status.value.detail,
        )
        tab.close()
    }

    @Test
    fun a_new_jump_host_key_is_asked_before_the_destination() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val store = InMemoryKnownHostsStore(listOf(known("dest")))
        val tab = newTab(scope, ChainConnector(), store, host("dest", proxy = "b"), host("b"))

        tab.start()
        advanceUntilIdle()
        val prompt = assertNotNull(tab.pendingHostKey.value)
        assertEquals("b", prompt.info.host)

        prompt.accept()
        advanceUntilIdle()

        assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
        assertEquals(1, store.entriesFor("b", 22).size)
        tab.close()
    }

    @Test
    fun a_changed_jump_host_key_blocks_and_names_the_jump_host() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val stored = KnownHostEntry("b", 22, "ecdsa-sha2-nistp256", "AAAAE2VjZHNhstored")
        val store = InMemoryKnownHostsStore(listOf(stored, known("dest")))
        val tab = newTab(scope, ChainConnector(), store, host("dest", proxy = "b"), host("b"))

        tab.start()
        advanceUntilIdle()

        assertNull(tab.pendingHostKey.value)
        assertEquals(TabPhase.FAILED, tab.status.value.phase)
        assertEquals("Bastión b: La clave del host ha cambiado: conexión bloqueada", tab.status.value.detail)
        assertEquals(ChangedHostKey(keyOf("b"), listOf(stored)), tab.changedHostKey.value)
        tab.close()
    }

    private companion object {
        fun keyOf(host: String) = HostKeyInfo(
            host = host,
            port = 22,
            keyType = "ssh-ed25519",
            fingerprintSha256 = "SHA256:$host",
            publicKeyBase64 = "AAAAC3NzaC1lZDI1NTE5AAAAI$host",
        )

        fun known(host: String) = keyOf(host).let { KnownHostEntry(it.host, it.port, it.keyType, it.publicKeyBase64) }
    }
}
