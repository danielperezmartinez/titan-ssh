package io.github.danielperezmartinez.titanssh.ssh

import io.github.danielperezmartinez.titanssh.terminal.AgentInstall
import io.github.danielperezmartinez.titanssh.terminal.execCollect
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The SFTP browser's calls (ADR-0021) against a real server: listing with
 * attributes, links, a read from an offset and the server's own errors.
 * Opt-in: set TITAN_SSH_TEST_HOST/USER (+ _PORT) and TITAN_SSH_TEST_KEY or
 * TITAN_SSH_TEST_PASSWORD. It writes and removes a scratch folder in the
 * user's home.
 */
class SshjSftpIntegrationTest {

    private fun connect(): SshSession? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val key = System.getenv("TITAN_SSH_TEST_KEY")
        val password = System.getenv("TITAN_SSH_TEST_PASSWORD")
        if (host == null || user == null || (key == null && password == null)) {
            println("[integration] sftp skipped: set TITAN_SSH_TEST_HOST/USER and _KEY or _PASSWORD")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val creds = if (key != null) {
            SshCredentials.PrivateKey(File(key).readText().toCharArray(), System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray())
        } else {
            SshCredentials.Password(password!!.toCharArray())
        }
        return runBlocking { createSshConnector().connect(SshEndpoint(host, port, user), creds, { true }, keepAliveSeconds = 0) }
    }

    @Test
    fun lists_with_attributes_and_reads_from_an_offset() {
        val session = connect() ?: return
        runBlocking {
            val sftp = assertNotNull(session.openSftp(), "the test host should offer SFTP")
            val home = sftp.canonicalize(".")
            val dir = "$home/titan-sftp-test-${Random.nextInt(1_000_000)}"
            try {
                sftp.mkdirs("$dir/sub")
                val bytes = Random.nextBytes(300_000)
                sftp.write("$dir/data.bin", bytes)

                val entries = sftp.listEntries(dir).associateBy { it.name }
                assertEquals(setOf("sub", "data.bin"), entries.keys)
                assertEquals(SftpEntry.Kind.DIRECTORY, entries.getValue("sub").kind)
                val file = entries.getValue("data.bin")
                assertEquals(SftpEntry.Kind.FILE, file.kind)
                assertEquals(bytes.size.toLong(), file.size)
                assertNotNull(file.modifiedEpochSeconds)
                assertEquals("$dir/data.bin", file.path)

                val offset = 123_456L
                val out = ByteArrayOutputStream()
                sftp.readFrom("$dir/data.bin", offset) { block, n -> out.write(block, 0, n) }
                assertContentEquals(bytes.copyOfRange(offset.toInt(), bytes.size), out.toByteArray())

                assertEquals(SftpEntry.Kind.DIRECTORY, sftp.stat("$dir/sub")?.kind)
                assertEquals(null, sftp.stat("$dir/missing"))

                val missing = assertFailsWith<SshSftpFailed> { sftp.listEntries("$dir/missing") }
                assertTrue(missing.message!!.isNotBlank())
                assertFailsWith<SshSftpFailed> { sftp.readFrom("$dir/missing", 0) { _, _ -> } }
            } finally {
                runCatching { sftp.remove("$dir/data.bin") }
                // SFTP has no rmdir here: remove the folders with the server's shell.
                val cleanup = if (AgentInstall.isWindowsSftpPath(dir)) {
                    "rmdir /s /q \"${dir.removePrefix("/").replace('/', '\\')}\""
                } else {
                    "rmdir '$dir/sub' '$dir'"
                }
                runCatching { session.execCollect(cleanup) }
                sftp.close()
                session.close()
            }
        }
    }
}
