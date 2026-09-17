package im.gar.titanssh.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * What it proves that headless tests cannot: authentication, host-key delivery,
 * and shell (PTY) I/O against a live server.
 */
class SshjIntegrationTest {

    @Test
    fun connects_authenticates_and_runs_a_shell_command() {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val keyPath = System.getenv("TITAN_SSH_TEST_KEY")
        if (host == null || user == null || keyPath == null) {
            println("[integration] skipped: set TITAN_SSH_TEST_HOST/USER/KEY to run")
            return
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val passphrase = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        val pem = File(keyPath).readText().toCharArray()

        runBlocking {
            val connector = createSshConnector()
            val session = connector.connect(
                endpoint = SshEndpoint(host, port, user),
                credentials = SshCredentials.PrivateKey(pem, passphrase),
                hostKeyVerifier = { info ->
                    println("[integration] host key ${info.keyType} ${info.fingerprintSha256}")
                    true // TOFU accept for the connectivity check
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
            // Collect for a fixed window so the command result (not just the
            // PTY echo of the typed line) is captured.
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
}
