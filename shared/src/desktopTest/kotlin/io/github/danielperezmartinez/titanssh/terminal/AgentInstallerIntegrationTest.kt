package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.runBlocking

/**
 * Installs a real `titan-agent` binary on the test host and checks the
 * detection/upload/checksum/idempotency/cleanup path end-to-end (ADR-0009 §7).
 * Works against Unix and Windows (Win32-OpenSSH) hosts. Opt-in: skips unless the
 * SSH host details and TITAN_AGENT_BIN (a built binary matching the host's
 * OS/arch) are provided. Removes what it uploaded afterwards.
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

    /** The installed file as SFTP names it (the installer only reports the shell's path). */
    private fun sftpPath(launch: AgentLaunch): String =
        if (launch.shell == RemoteShell.POSIX) launch.path else AgentInstall.windowsToSftpPath(launch.path)

    @Test
    fun installs_verifies_is_idempotent_and_removes_other_versions() {
        val (endpoint, creds, bin) = envOrSkip() ?: return
        val bytes = bin.readBytes()
        // Throwaway versions so the test never clobbers (or cleans up) a real install.
        val stamp = System.currentTimeMillis()
        val oldVersion = "itest-$stamp-a"
        val newVersion = "itest-$stamp-b"

        runBlocking {
            val session: SshSession = createSshConnector().connect(
                endpoint = endpoint,
                credentials = creds(),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )
            val sftp = assertNotNull(session.openSftp(), "the test host should offer SFTP")
            val uploaded = mutableListOf<String>()
            try {
                val installer = AgentInstaller(session, version = oldVersion, isStale = { false })
                val first = installer.ensureInstalled { bytes }
                assertTrue(first is AgentInstaller.Result.Installed, "expected Installed, got $first")
                assertTrue(first.uploaded, "first install should upload")
                uploaded += sftpPath(first.launch)
                println("[integration] installed at ${first.path} for ${first.target.slug} (${first.launch.shell})")

                // Second call must detect the matching checksum and skip the upload.
                val second = installer.ensureInstalled { bytes }
                assertTrue(second is AgentInstaller.Result.Installed && !second.uploaded, "second install should be a no-op, got $second")

                // The uploaded binary actually runs, launched the way AgentTransport does.
                val ver = session.exec(first.launch.command("--version"))
                    .output.fold(StringBuilder()) { a, c -> a.append(c.decodeToString()) }.toString()
                assertTrue(ver.contains("titan-agent"), "installed binary should report its version, got '$ver'")

                // A newer version replaces it and removes the old binary.
                val newer = AgentInstaller(session, version = newVersion, isStale = { it.startsWith("agent-$oldVersion-") })
                    .ensureInstalled { bytes }
                assertTrue(newer is AgentInstaller.Result.Installed && newer.uploaded, "newer version should upload, got $newer")
                uploaded += sftpPath(newer.launch)
                assertNull(sftp.size(sftpPath(first.launch)), "the old version's binary should be gone")
                assertEquals(bytes.size.toLong(), sftp.size(sftpPath(newer.launch)))
            } finally {
                // Leave the host as we found it.
                uploaded.forEach { runCatching { sftp.remove(it) } }
                sftp.close()
                session.close()
            }
        }
    }
}
