package im.gar.titanssh.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * End-to-end handshake against a real SSH host. Opt-in: skips unless the
 * connection details are provided (as -P properties forwarded to env vars by the
 * build). Credentials never live in the repo — the private key is read from a
 * path on disk given at run time.
 *
 * What it proves that headless tests cannot: authentication, host-key delivery
 * and TOFU verification, and shell (PTY) I/O against a live server.
 */
class SshjIntegrationTest {

    private class Params(
        val host: String,
        val port: Int,
        val user: String,
        val credentials: () -> SshCredentials,
    )

    /** Reads connection details, or null (and prints why) when the test should skip. */
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
        return Params(host, port, user) {
            SshCredentials.PrivateKey(File(keyPath).readText().toCharArray(), passphrase)
        }
    }

    @Test
    fun connects_authenticates_and_runs_a_shell_command() {
        val p = params() ?: return

        runBlocking {
            val session = createSshConnector().connect(
                endpoint = SshEndpoint(p.host, p.port, p.user),
                credentials = p.credentials(),
                hostKeyVerifier = { info ->
                    println("[integration] host key ${info.keyType} ${info.fingerprintSha256}")
                    true
                },
                keepAliveSeconds = 15,
            )

            assertEquals(
                SshConnectionState.CONNECTED,
                session.state.value,
                "authentication should have succeeded",
            )

            val shell = session.openShell()
            val output = StringBuilder()
            val reader = launch(Dispatchers.IO) {
                shell.output.collect { output.append(it.decodeToString()) }
            }

            shell.send("whoami\n".encodeToByteArray())
            // Collect for a fixed window so the command result (not just the PTY
            // echo of the typed line) is captured.
            delay(2500)

            shell.send("exit\n".encodeToByteArray())
            delay(300)
            reader.cancel()
            shell.close()
            session.close()

            println("[integration] shell output: ${output.toString().trim().take(200)}")
            assertTrue(output.isNotBlank(), "the remote shell should have produced output")
        }
    }

    @Test
    fun exec_channel_runs_a_command_without_a_pty() {
        val p = params() ?: return

        runBlocking {
            val session = createSshConnector().connect(
                endpoint = SshEndpoint(p.host, p.port, p.user),
                credentials = p.credentials(),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )

            // No PTY: `tty` reports "not a tty" on an exec channel, which is
            // exactly what the level-3 agent needs (raw stdout for the framed
            // protocol, ADR-0008).
            val channel = session.exec("echo titan-exec-ok; tty")
            val out = StringBuilder()
            val reader = launch(Dispatchers.IO) {
                channel.output.collect { out.append(it.decodeToString()) }
            }
            delay(2000)
            val status = channel.close()
            reader.cancel()
            session.close()

            println("[integration] exec output: ${out.toString().trim().take(200)} (status=$status)")
            assertTrue(out.contains("titan-exec-ok"), "exec should return the command's stdout")
            assertTrue(out.contains("not a tty"), "an exec channel must have no PTY")
        }
    }

    @Test
    fun known_hosts_tofu_persists_across_connections_and_rejects_a_mismatch() {
        val p = params() ?: return

        runBlocking {
            val file = File.createTempFile("titan-known-hosts-it", ".txt").apply { delete(); deleteOnExit() }
            val store = FileKnownHostsStore(file)

            // First contact: accept and remember.
            var prompted = 0
            createSshConnector().connect(
                endpoint = SshEndpoint(p.host, p.port, p.user),
                credentials = p.credentials(),
                hostKeyVerifier = KnownHostsVerifier(store) { prompted++; true },
                keepAliveSeconds = 0,
            ).close()
            assertEquals(1, prompted, "first contact should prompt once")
            assertTrue(
                store.entriesFor(p.host, p.port).isNotEmpty(),
                "the host key should now be persisted",
            )

            // Reconnect with a prompt that refuses new hosts: it must still connect
            // because the key is now known on disk.
            createSshConnector().connect(
                endpoint = SshEndpoint(p.host, p.port, p.user),
                credentials = p.credentials(),
                hostKeyVerifier = KnownHostsVerifier(store) { false },
                keepAliveSeconds = 0,
            ).close()

            // A store poisoned with a different key of the same type must be
            // rejected as a possible MITM.
            val stored = store.entriesFor(p.host, p.port).first()
            val poisoned = InMemoryKnownHostsStore(
                listOf(stored.copy(publicKeyBase64 = "AAAAtampered")),
            )
            assertFailsWith<SshHostKeyRejected> {
                createSshConnector().connect(
                    endpoint = SshEndpoint(p.host, p.port, p.user),
                    credentials = p.credentials(),
                    hostKeyVerifier = KnownHostsVerifier(poisoned) { false },
                    keepAliveSeconds = 0,
                )
            }
            println("[integration] known_hosts TOFU verified (persist + match + mismatch reject)")
        }
    }
}
