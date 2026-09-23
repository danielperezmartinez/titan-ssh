package im.gar.titanssh.terminal

import im.gar.titanssh.ssh.SshCredentials
import im.gar.titanssh.ssh.SshEndpoint
import im.gar.titanssh.ssh.createSshConnector
import im.gar.titanssh.ssh.SshSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.runBlocking

/**
 * Installs a real `titan-agent` binary on the test host and checks the
 * upload/checksum/idempotency path end-to-end (ADR-0008 §5). Opt-in: skips unless
 * the SSH host details and TITAN_AGENT_BIN (a built binary matching the host's
 * arch) are provided. Cleans up the uploaded file afterwards.
 */
class AgentInstallerIntegrationTest {

    private fun envOrSkip(): Triple<SshEndpoint, () -> SshCredentials, File>? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val keyPath = System.getenv("TITAN_SSH_TEST_KEY")
        val binPath = System.getenv("TITAN_AGENT_BIN")
        if (host == null || user == null || keyPath == null || binPath == null) {
            println("[integration] agent-install skipped: set TITAN_SSH_TEST_HOST/USER/KEY and TITAN_AGENT_BIN")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val passphrase = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        val creds = { SshCredentials.PrivateKey(File(keyPath).readText().toCharArray(), passphrase) as SshCredentials }
        return Triple(SshEndpoint(host, port, user), creds, File(binPath))
    }

    @Test
    fun installs_verifies_and_is_idempotent() {
        val (endpoint, creds, bin) = envOrSkip() ?: return
        val bytes = bin.readBytes()
        // A throwaway version so the test never clobbers a real install.
        val version = "itest-${System.currentTimeMillis()}"

        runBlocking {
            val session: SshSession = createSshConnector().connect(
                endpoint = endpoint,
                credentials = creds(),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )
            val installer = AgentInstaller(session, version = version)
            try {
                val first = installer.ensureInstalled { bytes }
                assertTrue(first is AgentInstaller.Result.Installed, "expected Installed, got $first")
                first as AgentInstaller.Result.Installed
                assertTrue(first.uploaded, "first install should upload")
                println("[integration] installed at ${first.path} for ${first.target.slug}")

                // Second call must detect the matching checksum and skip the upload.
                val second = installer.ensureInstalled { bytes }
                assertTrue(second is AgentInstaller.Result.Installed && !second.uploaded, "second install should be a no-op, got $second")

                // The uploaded binary actually runs.
                val ver = session.exec("${first.path} --version")
                    .output.fold(StringBuilder()) { a, c -> a.append(c.decodeToString()) }.toString()
                assertTrue(ver.contains("titan-agent"), "installed binary should report its version, got '$ver'")
            } finally {
                // Leave the host as we found it.
                val path = AgentInstall.remotePath(AgentInstall.DEFAULT_BASE_DIR, version, AgentTarget("linux", "amd64"))
                session.exec("rm -f $path").output.fold(0) { a, _ -> a }
                session.close()
            }
        }
    }
}
