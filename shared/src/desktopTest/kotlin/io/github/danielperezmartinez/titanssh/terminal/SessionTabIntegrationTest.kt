package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.ScriptBehavior
import io.github.danielperezmartinez.titanssh.config.ScriptPhase
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.ssh.HostKeyVerifier
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshHop
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * End-to-end check of the whole tab runtime — launch → connect → paint output →
 * send input — against a real SSH host. Opt-in, like [io.github.danielperezmartinez.titanssh.ssh
 * .SshjIntegrationTest]: it skips unless the connection details are provided as
 * `-P` properties (forwarded to env vars by the build); the private key is read
 * from disk at run time and never committed.
 *
 * What it proves that headless tests cannot: that [SessionManager]/[SessionTab]
 * drive a real connection, reach [TabPhase.CONNECTED], feed the shell output
 * through the [TerminalEmulator], and that keyboard input reaches the shell and
 * echoes back into the snapshot.
 */
class SessionTabIntegrationTest {

    private class Params(val host: String, val port: Int, val user: String, val keyPath: String, val passphrase: CharArray?)

    private fun params(): Params? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val keyPath = System.getenv("TITAN_SSH_TEST_KEY")
        if (host == null || user == null || keyPath == null) {
            println("[integration] skipped: set TITAN_SSH_TEST_HOST/USER/KEY to run")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val passphrase = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        return Params(host, port, user, keyPath, passphrase)
    }

    @Test
    fun tab_connects_paints_output_and_accepts_input() {
        val p = params() ?: return

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val host = Host(
                id = "h",
                alias = "test",
                hostname = p.host,
                port = p.port,
                username = p.user,
                auth = HostAuth.SoftwareKey(secretRef = "unused-in-test"),
            )
            val session = Session(id = "s", name = "integration", hostId = "h")
            val resolved = ResolvedConnection(
                session = session,
                host = host,
                endpoint = SshEndpoint(p.host, p.port, p.user),
                auth = host.auth,
                appearance = TerminalAppearance(),
                jumps = emptyList(),
            )

            val tab = SessionTab(
                id = "tab-it",
                resolved = resolved,
                connector = createSshConnector(),
                credentials = {
                    SshCredentials.PrivateKey(File(p.keyPath).readText().toCharArray(), p.passphrase)
                },
                knownHostsStore = InMemoryKnownHostsStore(),
                scope = scope,
                columns = 80,
                rows = 24,
            )

            // Auto-accept the first-contact host key (the UI does this via a prompt).
            val accepter = scope.launch { tab.pendingHostKey.collect { it?.accept() } }

            tab.start()

            val connected = withTimeoutOrNull(15_000) {
                while (tab.status.value.phase != TabPhase.CONNECTED) {
                    if (tab.status.value.phase == TabPhase.FAILED) break
                    kotlinx.coroutines.delay(100)
                }
                tab.status.value.phase
            }
            assertTrue(connected == TabPhase.CONNECTED, "the tab should reach CONNECTED, was ${tab.status.value}")

            // Wait for the shell to paint its prompt before typing: a remote PTY
            // drops input sent before the shell starts reading stdin, and a real
            // user only types once the prompt is on screen.
            val promptSeen = withTimeoutOrNull(10_000) {
                while (!snapshotText(tab).contains("$")) kotlinx.coroutines.delay(50)
                true
            } ?: false
            assertTrue(promptSeen, "the shell prompt should appear, was ${tab.status.value}")

            tab.sendBytes("echo titan-ok\n".encodeToByteArray())
            withTimeoutOrNull(4_000) {
                while (!snapshotText(tab).contains("titan-ok")) kotlinx.coroutines.delay(100)
            }

            val text = snapshotText(tab)
            tab.close()
            accepter.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            println("[integration] tab snapshot tail: ${text.trim().takeLast(120)}")
            assertTrue(text.contains("titan-ok"), "the echoed command output should appear in the terminal snapshot")
        }
    }

    /**
     * The host key prompt left open past sshj's 30 s key exchange timeout: the
     * attempt dies, yet accepting afterwards still connects the tab
     * ([[Conectar tras confirmar tarde la clave del servidor]]). Takes ~40 s.
     */
    @Test
    fun a_host_key_trusted_after_the_handshake_timed_out_still_connects() {
        val p = params() ?: return

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val host = Host(
                id = "h",
                alias = "test",
                hostname = p.host,
                port = p.port,
                username = p.user,
                auth = HostAuth.SoftwareKey(secretRef = "unused-in-test"),
            )
            val resolved = ResolvedConnection(
                session = Session(id = "s", name = "late-trust", hostId = "h"),
                host = host,
                endpoint = SshEndpoint(p.host, p.port, p.user),
                auth = host.auth,
                appearance = TerminalAppearance(),
                jumps = emptyList(),
            )
            val store = InMemoryKnownHostsStore()
            // Counts attempts, to prove the first one really died meanwhile.
            val real = createSshConnector()
            var attempts = 0
            val counting = object : SshConnector {
                override suspend fun connect(
                    endpoint: SshEndpoint,
                    credentials: SshCredentials,
                    hostKeyVerifier: HostKeyVerifier,
                    keepAliveSeconds: Int,
                    via: List<SshHop>,
                ): SshSession {
                    attempts++
                    return real.connect(endpoint, credentials, hostKeyVerifier, keepAliveSeconds)
                }
            }
            val tab = SessionTab(
                id = "tab-late",
                resolved = resolved,
                connector = counting,
                credentials = {
                    SshCredentials.PrivateKey(File(p.keyPath).readText().toCharArray(), p.passphrase)
                },
                knownHostsStore = store,
                scope = scope,
            )
            tab.start()

            val prompt = withTimeoutOrNull(15_000) {
                while (tab.pendingHostKey.value == null) kotlinx.coroutines.delay(100)
                tab.pendingHostKey.value
            }
            assertTrue(prompt != null, "the first contact should prompt, was ${tab.status.value}")

            kotlinx.coroutines.delay(40_000)
            assertTrue(tab.pendingHostKey.value === prompt, "the prompt should still be up")
            assertTrue(tab.status.value.phase == TabPhase.CONNECTING, "still connecting, was ${tab.status.value}")

            prompt!!.accept()
            val connected = withTimeoutOrNull(20_000) {
                while (tab.status.value.phase != TabPhase.CONNECTED) {
                    if (tab.status.value.phase == TabPhase.FAILED) break
                    kotlinx.coroutines.delay(100)
                }
                tab.status.value.phase
            }
            val status = tab.status.value
            val prompts = tab.pendingHostKey.value
            tab.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            assertTrue(connected == TabPhase.CONNECTED, "accepting late should connect, was $status")
            assertTrue(attempts == 2, "the first attempt timed out and a second one connected, was $attempts")
            assertTrue(prompts == null, "no second prompt")
            assertTrue(store.entriesFor(p.host, p.port).size == 1, "the key is saved once")
        }
    }

    /** Empty store: the start-script test uses no secret refs. */
    private class EmptySecretStore : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) {}
        override suspend fun get(ref: SecretRef): ByteArray? = null
        override suspend fun remove(ref: SecretRef) {}
        override suspend fun contains(ref: SecretRef): Boolean = false
    }

    @Test
    fun start_scripts_run_on_connect_with_initial_cd() {
        val p = params() ?: return

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val host = Host(
                id = "h",
                alias = "test",
                hostname = p.host,
                port = p.port,
                username = p.user,
                auth = HostAuth.SoftwareKey(secretRef = "unused-in-test"),
            )
            // `echo "PWD=$(pwd)"` proves both the initial cd (PWD=/tmp) and that
            // the script actually ran: only the command's OUTPUT reads "PWD=/tmp",
            // the echoed command line still shows the literal `$(pwd)`.
            val session = Session(
                id = "s",
                name = "scripts",
                hostId = "h",
                initialDirectory = "/tmp",
                scripts = listOf(
                    SessionScript(
                        id = "sc1",
                        label = "show pwd",
                        phase = ScriptPhase.ON_SHELL_START,
                        body = "echo \"PWD=\$(pwd)\"",
                        behavior = ScriptBehavior(waitForCompletion = true),
                    ),
                ),
            )
            val resolved = ResolvedConnection(
                session = session,
                host = host,
                endpoint = SshEndpoint(p.host, p.port, p.user),
                auth = host.auth,
                appearance = TerminalAppearance(),
                jumps = emptyList(),
            )

            val tab = SessionTab(
                id = "tab-scripts",
                resolved = resolved,
                connector = createSshConnector(),
                credentials = {
                    SshCredentials.PrivateKey(File(p.keyPath).readText().toCharArray(), p.passphrase)
                },
                knownHostsStore = InMemoryKnownHostsStore(),
                scope = scope,
                columns = 80,
                rows = 24,
                automation = StartScriptAutomation(EmptySecretStore()),
            )

            val accepter = scope.launch { tab.pendingHostKey.collect { it?.accept() } }
            tab.start()

            val ran = withTimeoutOrNull(15_000) {
                while (!snapshotText(tab).contains("PWD=/tmp")) {
                    if (tab.status.value.phase == TabPhase.FAILED) break
                    kotlinx.coroutines.delay(100)
                }
                snapshotText(tab).contains("PWD=/tmp")
            }
            // Let the sentinel's echo and output arrive: the tab must hide both.
            kotlinx.coroutines.delay(1_500)

            val text = snapshotText(tab)
            tab.close()
            accepter.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            println("[integration] scripts snapshot tail: ${text.trim().takeLast(160)}")
            assertTrue(ran == true, "the start script should cd to /tmp and print PWD=/tmp; status=${tab.status.value}")
            assertTrue(!text.contains("__TITAN_"), "the completion sentinel must not show in the terminal:\n$text")
        }
    }

    private fun snapshotText(tab: SessionTab): String {
        val snap = tab.snapshot.value
        return (snap.scrollback + snap.screen).joinToString("\n") { line ->
            line.joinToString("") { it.char.toString() }
        }
    }
}
