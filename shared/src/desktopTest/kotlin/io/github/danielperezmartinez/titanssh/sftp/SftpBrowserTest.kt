package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshSftpFailed
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SftpBrowserTest {

    /** A file system in memory; [failAfter] makes a read die after that many bytes, once. */
    private class FakeSftp(
        val files: Map<String, ByteArray>,
        val dirs: Map<String, List<String>>,
        val home: String = "/home/demo",
    ) : SshSftp {
        var failAfter: Long? = null
        var failWith: () -> Exception = { IOException("connection reset") }

        /** When set, the read hangs here (a dead connection) instead of failing. */
        var hangAt: Long? = null
        val readOffsets = mutableListOf<Long>()
        val reachedHang = CompletableDeferred<Unit>()

        override suspend fun canonicalize(path: String) = if (path == ".") home else path.trimEnd('/').ifEmpty { "/" }
        override suspend fun size(path: String) = files[path]?.size?.toLong()
        override suspend fun list(directory: String) = dirs[directory].orEmpty()
        override suspend fun listEntries(directory: String): List<SftpEntry> {
            val names = dirs[directory] ?: throw SshSftpFailed("No such file")
            return names.map { name ->
                val path = "$directory/$name"
                val file = files[path]
                SftpEntry(name, path, if (file != null) SftpEntry.Kind.FILE else SftpEntry.Kind.DIRECTORY, file?.size?.toLong(), null, null)
            }
        }

        override suspend fun stat(path: String): SftpEntry? = when {
            files.containsKey(path) -> SftpEntry(path.substringAfterLast('/'), path, SftpEntry.Kind.FILE, files.getValue(path).size.toLong(), null, null)
            dirs.containsKey(path) -> SftpEntry(path.substringAfterLast('/'), path, SftpEntry.Kind.DIRECTORY, null, null, null)
            else -> null
        }

        override suspend fun readFrom(path: String, offset: Long, onBlock: suspend (ByteArray, Int) -> Unit) {
            readOffsets += offset
            val data = files[path] ?: throw SshSftpFailed("No such file")
            var at = offset
            while (at < data.size) {
                hangAt?.let { if (at >= it) { hangAt = null; reachedHang.complete(Unit); awaitCancellation() } }
                failAfter?.let { if (at >= it) { failAfter = null; throw failWith() } }
                val n = minOf(BLOCK, data.size - at.toInt())
                onBlock(data.copyOfRange(at.toInt(), at.toInt() + n), n)
                at += n
            }
        }

        override suspend fun mkdirs(directory: String) = Unit
        override suspend fun write(path: String, bytes: ByteArray) = Unit
        override suspend fun read(path: String) = files.getValue(path)
        override suspend fun chmod(path: String, mode: Int) = Unit
        override suspend fun rename(from: String, to: String) = Unit
        override suspend fun remove(path: String) = Unit
        override suspend fun close() = Unit
    }

    private class MemoryStore : DownloadStore {
        val saved = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()

        override fun create(fileName: String): DownloadTarget = object : DownloadTarget {
            val out = ByteArrayOutputStream()
            override val name = fileName
            override val location = "Descargas/$fileName"
            override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)
            override fun complete() { saved[fileName] = out.toByteArray() }
            override fun discard() { discarded += fileName }
            override fun open() = true
        }
    }

    private val payload = ByteArray(10 * BLOCK + 123) { (it * 31 % 251).toByte() }

    private fun fake() = FakeSftp(
        files = mapOf("/home/demo/datos.bin" to payload),
        dirs = mapOf("/home/demo" to listOf("datos.bin", "proyecto"), "/home/demo/proyecto" to emptyList()),
    )

    /**
     * A browser on the test's scheduler. Not in backgroundScope, whose work
     * advanceUntilIdle does not wait for, and not a child of the test, which
     * would wait for its endless collectors.
     */
    private fun TestScope.browser(store: MemoryStore, initial: String? = null): SftpBrowser {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SftpBrowser(CoroutineScope(dispatcher + Job()), initial, { store }, io = dispatcher)
    }

    private val SftpBrowser.file get() = listing.value.entries.first { it.name == "datos.bin" }

    @Test
    fun lists_the_start_folder_once_a_channel_is_attached() = runTest {
        val b = browser(MemoryStore(), initial = "~/proyecto")
        b.attach(fake())
        advanceUntilIdle()
        assertEquals("/home/demo/proyecto", b.listing.value.path)

        b.up()
        advanceUntilIdle()
        assertEquals("/home/demo", b.listing.value.path)
        assertEquals(setOf("datos.bin", "proyecto"), b.listing.value.entries.map { it.name }.toSet())
    }

    @Test
    fun a_start_folder_that_does_not_exist_falls_back_to_home() = runTest {
        val b = browser(MemoryStore(), initial = "/no/existe")
        b.attach(fake())
        advanceUntilIdle()
        assertEquals("/home/demo", b.listing.value.path)
    }

    @Test
    fun a_folder_that_cannot_be_listed_says_why_and_keeps_the_last_one() = runTest {
        val b = browser(MemoryStore())
        b.attach(fake())
        advanceUntilIdle()
        b.open("/root")
        advanceUntilIdle()
        assertEquals("/home/demo", b.listing.value.path)
        assertTrue(b.listing.value.error!!.contains("/root"))
    }

    @Test
    fun downloads_a_file_whole() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        b.attach(fake())
        advanceUntilIdle()
        b.download(b.file)
        advanceUntilIdle()
        val t = b.transfers.value.single()
        assertEquals(SftpTransfer.State.DONE, t.state)
        assertEquals(payload.size.toLong(), t.transferred)
        assertContentEquals(payload, store.saved.getValue("datos.bin"))
    }

    @Test
    fun a_dropped_connection_resumes_from_the_last_byte_on_the_next_channel() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val first = fake().apply { failAfter = 4L * BLOCK }
        var lost = 0
        b.attach(first) { lost++ }
        advanceUntilIdle()
        b.download(b.file)
        // The read failed like on a dead link: the browser tells the tab at once.
        advanceUntilIdle()
        assertEquals(1, lost)
        assertEquals(SftpTransfer.State.WAITING, b.transfers.value.single().state)
        assertEquals(4L * BLOCK, b.transfers.value.single().transferred)

        val second = fake()
        b.attach(second)
        advanceUntilIdle()
        assertEquals(listOf(4L * BLOCK), second.readOffsets)
        assertEquals(SftpTransfer.State.DONE, b.transfers.value.single().state)
        assertContentEquals(payload, store.saved.getValue("datos.bin"))
    }

    @Test
    fun a_read_stuck_on_a_dead_connection_stops_when_the_channel_is_detached() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val first = fake().apply { hangAt = 3L * BLOCK }
        b.attach(first)
        advanceUntilIdle()
        b.download(b.file)
        advanceUntilIdle()
        assertTrue(first.reachedHang.isCompleted)
        b.detach(first)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.WAITING, b.transfers.value.single().state)

        val second = fake()
        b.attach(second)
        advanceUntilIdle()
        assertEquals(listOf(3L * BLOCK), second.readOffsets)
        assertContentEquals(payload, store.saved.getValue("datos.bin"))
    }

    @Test
    fun the_servers_answer_fails_the_download_at_once_and_deletes_the_partial_file() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val sftp = fake().apply {
            failAfter = 2L * BLOCK
            failWith = { SshSftpFailed("Permission denied") }
        }
        b.attach(sftp)
        advanceUntilIdle()
        b.download(b.file)
        advanceUntilIdle()
        val t = b.transfers.value.single()
        assertEquals(SftpTransfer.State.FAILED, t.state)
        assertEquals("Permission denied", t.error)
        assertEquals(listOf("datos.bin"), store.discarded)
    }

    @Test
    fun an_error_that_comes_back_at_the_same_byte_on_every_connection_fails() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        var reconnections = 0
        // Each new connection fails again at the same point, as the tab reattaches.
        fun attachFailing() = b.attach(fake().apply { failAfter = BLOCK.toLong() }) { reconnections++ }
        attachFailing()
        advanceUntilIdle()
        b.download(b.file)
        advanceUntilIdle()
        while (b.transfers.value.single().state == SftpTransfer.State.WAITING) {
            attachFailing()
            advanceUntilIdle()
        }
        assertEquals(SftpTransfer.State.FAILED, b.transfers.value.single().state)
        assertEquals(5, reconnections)
        assertEquals(listOf("datos.bin"), store.discarded)
    }

    @Test
    fun a_listing_that_fails_like_a_dead_link_asks_to_reconnect_and_lists_again() = runTest {
        val b = browser(MemoryStore())
        var lost = 0
        val dead = object : SshSftp by fake() {
            override suspend fun canonicalize(path: String): String = throw IOException("Timeout expired")
        }
        b.attach(dead) { lost++ }
        advanceUntilIdle()
        assertEquals(1, lost)
        assertEquals(null, b.listing.value.error)
        b.attach(fake())
        advanceUntilIdle()
        assertEquals("/home/demo", b.listing.value.path)
    }

    @Test
    fun a_file_that_changed_while_disconnected_is_not_resumed() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val first = fake().apply { failAfter = 2L * BLOCK }
        b.attach(first)
        advanceUntilIdle()
        b.download(b.file)
        testScheduler.runCurrent()
        b.detach(first)
        advanceUntilIdle()

        val changed = FakeSftp(
            files = mapOf("/home/demo/datos.bin" to payload + payload),
            dirs = mapOf("/home/demo" to listOf("datos.bin")),
        )
        b.attach(changed)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.FAILED, b.transfers.value.single().state)
        assertEquals(listOf("datos.bin"), store.discarded)
    }

    @Test
    fun cancelling_deletes_the_partial_file() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val sftp = fake().apply { hangAt = 2L * BLOCK }
        b.attach(sftp)
        advanceUntilIdle()
        b.download(b.file)
        advanceUntilIdle()
        b.cancel(b.transfers.value.single().id)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.CANCELLED, b.transfers.value.single().state)
        assertEquals(listOf("datos.bin"), store.discarded)
        assertTrue(store.saved.isEmpty())
    }

    private companion object {
        const val BLOCK = 1024
    }
}
