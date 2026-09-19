package im.gar.titanssh.terminal

import im.gar.titanssh.config.Host
import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.config.ScriptBehavior
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.Session
import im.gar.titanssh.config.SessionScript
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SecretStore
import im.gar.titanssh.ssh.InMemoryKnownHostsStore
import im.gar.titanssh.ssh.SshCredentials
import im.gar.titanssh.ssh.SshEndpoint
import im.gar.titanssh.ssh.createSshConnector
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
 * send input — against a real SSH host. Opt-in, like [im.gar.titanssh.ssh
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
                proxyJump = null,
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
                proxyJump = null,
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

            val text = snapshotText(tab)
            tab.close()
            accepter.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

            println("[integration] scripts snapshot tail: ${text.trim().takeLast(160)}")
            assertTrue(ran == true, "the start script should cd to /tmp and print PWD=/tmp; status=${tab.status.value}")
        }
    }

    private fun snapshotText(tab: SessionTab): String {
        val snap = tab.snapshot.value
        return (snap.scrollback + snap.screen).joinToString("\n") { line ->
            line.joinToString("") { it.char.toString() }
        }
    }
}
