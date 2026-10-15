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
                // The server's shell removes whatever the test left, whatever failed.
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

    /** The writing calls of ADR-0024: a resumed streamed write, folders, rename and delete. */
    @Test
    fun writes_from_an_offset_makes_renames_and_removes() {
        val session = connect() ?: return
        runBlocking {
            val sftp = assertNotNull(session.openSftp(), "the test host should offer SFTP")
            val home = sftp.canonicalize(".")
            val dir = "$home/titan-sftp-write-${Random.nextInt(1_000_000)}"
            try {
                sftp.makeDirectory(dir)
                assertFailsWith<SshSftpFailed> { sftp.makeDirectory(dir) }
                assertTrue(sftp.writeAhead > 0)

                // A first part, then the rest from where it stopped, as a resumed upload.
                val bytes = Random.nextBytes(700_000)
                val cut = 250_000
                fun source(from: Int): suspend (ByteArray) -> Int {
                    var at = from
                    return { buffer ->
                        if (at >= bytes.size) {
                            -1
                        } else {
                            val n = minOf(buffer.size, bytes.size - at)
                            bytes.copyInto(buffer, 0, at, at + n)
                            at += n
                            n
                        }
                    }
                }
                var sent = 0
                sftp.writeFrom("$dir/part", 0) { buffer ->
                    if (sent >= cut) return@writeFrom -1
                    val n = minOf(buffer.size, cut - sent)
                    bytes.copyInto(buffer, 0, sent, sent + n)
                    sent += n
                    n
                }
                assertEquals(cut.toLong(), sftp.stat("$dir/part")?.size)
                sftp.writeFrom("$dir/part", cut.toLong(), source(cut))
                assertContentEquals(bytes, sftp.read("$dir/part"))

                // Offset 0 truncates what was there.
                sftp.writeFrom("$dir/part", 0, source(bytes.size - 10))
                assertEquals(10L, sftp.stat("$dir/part")?.size)

                sftp.makeDirectory("$dir/sub")
                sftp.rename("$dir/part", "$dir/sub/final")
                assertEquals(null, sftp.stat("$dir/part"))
                assertEquals(10L, sftp.stat("$dir/sub/final")?.size)
                sftp.write("$dir/other", byteArrayOf(1))
                assertFailsWith<SshSftpFailed> { sftp.rename("$dir/other", "$dir/sub/final") }

                assertFailsWith<SshSftpFailed> { sftp.removeDirectory("$dir/sub") }
                sftp.remove("$dir/sub/final")
                sftp.removeDirectory("$dir/sub")
                sftp.remove("$dir/other")
                assertFailsWith<SshSftpFailed> { sftp.remove("$dir/other") }
                sftp.removeDirectory(dir)
                assertEquals(null, sftp.stat(dir))
            } finally {
                if (sftp.stat(dir) != null) {
                    val cleanup = if (AgentInstall.isWindowsSftpPath(dir)) {
                        "rmdir /s /q \"${dir.removePrefix("/").replace('/', '\\')}\""
                    } else {
                        "rm -rf '$dir'"
                    }
                    runCatching { session.execCollect(cleanup) }
                }
                sftp.close()
                session.close()
            }
        }
    }
}
