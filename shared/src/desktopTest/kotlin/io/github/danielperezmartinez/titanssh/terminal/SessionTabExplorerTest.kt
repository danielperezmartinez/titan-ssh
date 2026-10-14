package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshConnectFailed
import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

/**
 * A file browser opened from a terminal tab
 * ([[Abrir el explorador SFTP desde una sesión abierta]]): it opens its SFTP
 * channel on the terminal's own connection, follows it across reconnects, and
 * connects on its own once the terminal tab is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTabExplorerTest {

    private class FakeShell : SshShell {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: Flow<ByteArray> = ch.receiveAsFlow()
        override suspend fun send(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() { ch.close() }
        fun end() { ch.close() }
    }

    private class FakeSftp(private val home: String) : SshSftp {
        var closed = false
        override suspend fun canonicalize(path: String) = if (path == ".") home else path
        override suspend fun size(path: String): Long? = null
        override suspend fun list(directory: String) = listOf("a.txt")
        override suspend fun listEntries(directory: String) =
            listOf(SftpEntry(name = "a.txt", path = "$directory/a.txt", kind = SftpEntry.Kind.FILE, size = 1, modifiedEpochSeconds = null, permissions = null))
        override suspend fun stat(path: String): SftpEntry? =
            if (path == "/srv/app") SftpEntry(name = "app", path = path, kind = SftpEntry.Kind.DIRECTORY, size = null, modifiedEpochSeconds = null, permissions = null) else null
        override suspend fun mkdirs(directory: String) = Unit
        override suspend fun write(path: String, bytes: ByteArray) = Unit
        override suspend fun read(path: String) = ByteArray(0)
        override suspend fun chmod(path: String, mode: Int) = Unit
        override suspend fun rename(from: String, to: String) = Unit
        override suspend fun remove(path: String) = Unit
        override suspend fun close() { closed = true }
    }

    private class FakeSession(home: String = "/home/demo") : SshSession {
        private val _state = MutableStateFlow(SshConnectionState.CONNECTED)
        override val state = _state.asStateFlow()
        val shell = FakeShell()
        val sftp = FakeSftp(home)
        var sftpOpened = 0
        var closed = false
        override suspend fun openShell(columns: Int, rows: Int): SshShell = shell
        override suspend fun openSftp(): SshSftp {
            sftpOpened++
            return sftp
        }
        override suspend fun close() {
            closed = true
            _state.value = SshConnectionState.DISCONNECTED
            shell.end()
        }
        fun drop() {
            _state.value = SshConnectionState.DISCONNECTED
            shell.end()
        }
    }

    private class FakeConnector(sessions: List<FakeSession>) : SshConnector {
        private val plan = ArrayDeque(sessions)
        var calls = 0
        override suspend fun connect(
            endpoint: SshEndpoint,
            credentials: SshCredentials,
            hostKeyVerifier: HostKeyVerifier,
            keepAliveSeconds: Int,
            via: List<SshHop>,
        ): SshSession {
            calls++
            return plan.removeFirstOrNull() ?: throw SshConnectFailed("no more sessions")
        }
    }

    private fun resolved(type: SessionType = SessionType.TERMINAL, directory: String? = null): ResolvedConnection {
        val host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))
        return ResolvedConnection(
            session = Session(id = "s", name = "n", hostId = "h", type = type, initialDirectory = directory),
            host = host,
            endpoint = SshEndpoint("x", 22, "u"),
            auth = host.auth,
            appearance = TerminalAppearance(),
            jumps = emptyList(),
        )
    }

    private val policy = ReconnectPolicy(maxAttempts = 5, initialBackoffMillis = 10, maxBackoffMillis = 20, dropGraceMillis = 100)

    private fun tab(
        id: String,
        scope: CoroutineScope,
        connector: SshConnector,
        resolved: ResolvedConnection,
        explorerOf: SessionTab? = null,
        timeSource: kotlin.time.TimeSource,
    ) = SessionTab(
        id = id,
        resolved = resolved,
        connector = connector,
        credentials = { SshCredentials.Password("pw".toCharArray()) },
        knownHostsStore = InMemoryKnownHostsStore(),
        scope = scope,
        reconnect = policy,
        timeSource = timeSource,
        explorerOf = explorerOf,
    )

    @Test
    fun borrows_the_terminals_connection_and_follows_it_across_a_reconnect() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val s2 = FakeSession()
        val terminalConnector = FakeConnector(listOf(s1, s2))
        val ownConnector = FakeConnector(emptyList())
        val terminal = tab("t", scope, terminalConnector, resolved(), timeSource = testScheduler.timeSource)
        terminal.start()
        advanceUntilIdle()

        val explorer = tab(
            "e", scope, ownConnector, resolved(SessionType.SFTP, "/srv/app"),
            explorerOf = terminal, timeSource = testScheduler.timeSource,
        )
        explorer.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, explorer.status.value.phase)
        assertEquals(0, ownConnector.calls, "no connection of its own while the terminal has one")
        assertEquals(1, s1.sftpOpened)
        assertEquals("/srv/app", explorer.sftp!!.listing.value.path)

        // The terminal reconnects: the browser moves to its new connection.
        s1.drop()
        advanceUntilIdle()
        assertSame(s2, terminal.liveSession.value)
        assertEquals(1, s2.sftpOpened)
        assertEquals(TabPhase.CONNECTED, explorer.status.value.phase)
        assertEquals(0, ownConnector.calls)

        // Closing the browser leaves the terminal's connection alone.
        explorer.close()
        advanceUntilIdle()
        assertFalse(s2.closed)
        assertEquals(TabPhase.CONNECTED, terminal.status.value.phase)
        terminal.close()
    }

    @Test
    fun goes_on_with_its_own_connection_once_the_terminal_tab_closes() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val own = FakeSession()
        val terminal = tab("t", scope, FakeConnector(listOf(s1)), resolved(), timeSource = testScheduler.timeSource)
        terminal.start()
        advanceUntilIdle()
        val ownConnector = FakeConnector(listOf(own))
        val explorer = tab(
            "e", scope, ownConnector, resolved(SessionType.SFTP),
            explorerOf = terminal, timeSource = testScheduler.timeSource,
        )
        explorer.start()
        advanceUntilIdle()
        assertEquals(1, s1.sftpOpened)

        terminal.close()
        advanceUntilIdle()
        assertEquals(1, ownConnector.calls)
        assertEquals(1, own.sftpOpened)
        assertEquals(TabPhase.CONNECTED, explorer.status.value.phase)
        assertEquals("/home/demo", explorer.sftp!!.listing.value.path)
        explorer.close()
    }

    private class OneSecretStore : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) = Unit
        override suspend fun get(ref: SecretRef): ByteArray = "pw".encodeToByteArray()
        override suspend fun remove(ref: SecretRef) = Unit
        override suspend fun contains(ref: SecretRef) = true
    }

    @Test
    fun the_manager_opens_it_beside_the_terminal_without_holding_an_instance() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val s1 = FakeSession()
        val manager = SessionManager(scope, FakeConnector(listOf(s1)), CredentialResolver(OneSecretStore()), InMemoryKnownHostsStore(), reconnect = policy)
        val terminal = manager.open(resolved())
        advanceUntilIdle()
        terminal.rename("logs")

        val explorer = manager.openExplorer(terminal)
        advanceUntilIdle()
        assertSame(explorer, manager.tabs.value.last())
        assertEquals(explorer.id, manager.activeId.value)
        assertEquals("logs · archivos", explorer.title)
        assertEquals(mapOf("s" to setOf(1)), manager.openInstances.value, "the browser is no instance of the session")
        assertEquals(listOf(terminal), manager.tabsOf("s", 1))
        // A plain shell sends no OSC 7 here: the browser says it starts at home.
        assertEquals("/home/demo", explorer.sftp!!.listing.value.path)
        assertEquals(
            "No se sabe en qué carpeta está la terminal: se muestra la carpeta de inicio",
            explorer.sftp!!.listing.value.notice,
        )

        // Ending the instance closes its browser too, but not when closing another instance.
        manager.closeTabsOf("s", instance = 2)
        assertEquals(2, manager.tabs.value.size)
        manager.closeTabsOf("s")
        advanceUntilIdle()
        assertEquals(emptyList(), manager.tabs.value)
    }

    @Test
    fun a_terminal_closed_before_it_connected_lets_the_browser_connect_on_its_own() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val terminal = tab("t", scope, FakeConnector(emptyList()), resolved(), timeSource = testScheduler.timeSource)
        val own = FakeSession()
        val ownConnector = FakeConnector(listOf(own))
        val explorer = tab(
            "e", scope, ownConnector, resolved(SessionType.SFTP),
            explorerOf = terminal, timeSource = testScheduler.timeSource,
        )
        explorer.start()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTING, explorer.status.value.phase, "it waits for the terminal")
        terminal.close()
        advanceUntilIdle()
        assertEquals(TabPhase.CONNECTED, explorer.status.value.phase)
        assertEquals(1, ownConnector.calls)
        explorer.close()
    }
}
