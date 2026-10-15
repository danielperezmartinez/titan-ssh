package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshSftpFailed
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SftpBrowserTest {

    /**
     * A file system in memory. [failAfter] makes a read die after that many
     * bytes, once; [writeFailAfter] does the same to a write. [links] are links
     * to folders (link path → folder path).
     */
    private class FakeSftp(
        files: Map<String, ByteArray>,
        dirs: Map<String, List<String>>,
        links: Map<String, String> = emptyMap(),
        val home: String = "/home/demo",
    ) : SshSftp {
        val files = files.toMutableMap()
        val dirs = dirs.mapValues { it.value.toMutableList() }.toMutableMap()
        val links = links.toMutableMap()
        var failAfter: Long? = null
        var failWith: () -> Exception = { IOException("connection reset") }
        var writeFailAfter: Long? = null

        /** When set, the write hangs once it holds that many bytes (a slow link), until cancelled. */
        var writeHangAt: Long? = null
        val reachedWriteHang = CompletableDeferred<Unit>()

        /** When set, the read hangs here (a dead connection) instead of failing. */
        var hangAt: Long? = null
        val readOffsets = mutableListOf<Long>()
        val writeOffsets = mutableListOf<Long>()
        val reachedHang = CompletableDeferred<Unit>()
        override var writeAhead = 0L

        private fun parentOf(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
        private fun nameOf(path: String) = path.substringAfterLast('/')
        private fun exists(path: String) = path in files || path in dirs || path in links

        override suspend fun canonicalize(path: String) = if (path == ".") home else path.trimEnd('/').ifEmpty { "/" }
        override suspend fun size(path: String) = files[path]?.size?.toLong()
        override suspend fun list(directory: String) = dirs[directory].orEmpty()
        override suspend fun listEntries(directory: String): List<SftpEntry> {
            val real = links[directory] ?: directory
            val names = dirs[real] ?: throw SshSftpFailed("No such file")
            return names.map { name ->
                val path = "$directory/$name"
                val file = files["$real/$name"]
                val kind = when {
                    file != null -> SftpEntry.Kind.FILE
                    "$real/$name" in links -> SftpEntry.Kind.LINK_TO_DIRECTORY
                    else -> SftpEntry.Kind.DIRECTORY
                }
                SftpEntry(name, path, kind, file?.size?.toLong(), null, null)
            }
        }

        override suspend fun stat(path: String): SftpEntry? {
            val real = links[path] ?: path
            return when {
                files.containsKey(real) -> SftpEntry(nameOf(path), path, SftpEntry.Kind.FILE, files.getValue(real).size.toLong(), null, null)
                dirs.containsKey(real) -> SftpEntry(nameOf(path), path, SftpEntry.Kind.DIRECTORY, null, null, null)
                else -> null
            }
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

        override suspend fun writeFrom(path: String, offset: Long, nextBlock: suspend (ByteArray) -> Int) {
            writeOffsets += offset
            val parent = dirs[parentOf(path)] ?: throw SshSftpFailed("No such file")
            val out = ByteArrayOutputStream()
            if (offset > 0) {
                val held = files[path] ?: throw SshSftpFailed("No such file")
                out.write(held, 0, offset.toInt())
            }
            if (nameOf(path) !in parent) parent += nameOf(path)
            files[path] = out.toByteArray()
            val buffer = ByteArray(BLOCK)
            while (true) {
                val n = nextBlock(buffer)
                if (n < 0) break
                writeFailAfter?.let { if (out.size() >= it) { writeFailAfter = null; throw IOException("connection reset") } }
                writeHangAt?.let { if (out.size() >= it) { writeHangAt = null; reachedWriteHang.complete(Unit); awaitCancellation() } }
                out.write(buffer, 0, n)
                files[path] = out.toByteArray()
            }
        }

        override suspend fun makeDirectory(path: String) {
            if (exists(path)) throw SshSftpFailed("Failure")
            val parent = dirs[parentOf(path)] ?: throw SshSftpFailed("No such file")
            parent += nameOf(path)
            dirs[path] = mutableListOf()
        }

        override suspend fun removeDirectory(path: String) {
            val content = dirs[path] ?: throw SshSftpFailed("No such file")
            if (content.isNotEmpty()) throw SshSftpFailed("Failure")
            dirs.remove(path)
            dirs[parentOf(path)]?.remove(nameOf(path))
        }

        override suspend fun rename(from: String, to: String) {
            if (!exists(from)) throw SshSftpFailed("No such file")
            if (exists(to)) throw SshSftpFailed("Failure")
            val target = dirs[parentOf(to)] ?: throw SshSftpFailed("No such file")
            fun <V> moveKeys(map: MutableMap<String, V>) {
                map.keys.filter { it == from || it.startsWith("$from/") }.forEach { key ->
                    map[to + key.removePrefix(from)] = map.remove(key)!!
                }
            }
            moveKeys(files)
            moveKeys(dirs)
            moveKeys(links)
            dirs[parentOf(from)]?.remove(nameOf(from))
            target += nameOf(to)
        }

        override suspend fun remove(path: String) {
            when {
                files.remove(path) != null || links.remove(path) != null -> dirs[parentOf(path)]?.remove(nameOf(path))
                else -> throw SshSftpFailed("No such file")
            }
        }

        override suspend fun mkdirs(directory: String) = Unit
        override suspend fun write(path: String, bytes: ByteArray) = Unit
        override suspend fun read(path: String) = files.getValue(path)
        override suspend fun chmod(path: String, mode: Int) = Unit
        override suspend fun close() = Unit
    }

    private class MemoryStore : DownloadStore {
        /** What was saved, by its path in Downloads (`proyecto/src/a.txt`). */
        val saved = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()
        val folders = mutableListOf<String>()
        val discardedFolders = mutableListOf<String>()

        override fun create(fileName: String): DownloadTarget = target(fileName)

        private fun target(path: String): DownloadTarget = object : DownloadTarget {
            val out = ByteArrayOutputStream()
            override val name = path.substringAfterLast('/')
            override val location = "Descargas/$path"
            override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)
            override fun complete() { saved[path] = out.toByteArray() }
            override fun discard() {
                saved.remove(path)
                discarded += path
            }
            override fun open() = true
        }

        override fun createFolder(name: String): DownloadFolder {
            folders += name
            return object : DownloadFolder {
                override val name = name
                override val location = "Descargas/$name"
                override fun create(subfolder: String, fileName: String) =
                    target(listOf(name, subfolder, fileName).filter { it.isNotEmpty() }.joinToString("/"))
                override fun makeFolder(subfolder: String) {
                    folders += "$name/$subfolder"
                }
                override fun discard() {
                    discardedFolders += name
                }
                override fun open() = true
            }
        }
    }

    /** A file of this device to upload; [opened] records each offset it was opened at. */
    private class BytesSource(override val name: String, val data: ByteArray) : UploadSource {
        val opened = mutableListOf<Long>()
        override val size: Long get() = data.size.toLong()

        override fun open(offset: Long): UploadReader {
            opened += offset
            var at = offset.toInt()
            return object : UploadReader {
                override fun read(buffer: ByteArray): Int {
                    if (at >= data.size) return -1
                    val n = minOf(buffer.size, data.size - at)
                    data.copyInto(buffer, 0, at, at + n)
                    at += n
                    return n
                }

                override fun close() = Unit
            }
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
        assertEquals("No se puede abrir /no/existe: se muestra la carpeta de inicio", b.listing.value.notice)

        // The note goes with the start folder only.
        b.open("/home/demo/proyecto")
        advanceUntilIdle()
        assertNull(b.listing.value.notice)
    }

    @Test
    fun the_start_note_of_an_unknown_folder_shows_with_home() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val b = SftpBrowser(CoroutineScope(dispatcher + Job()), null, { MemoryStore() }, startNote = "no se sabe", io = dispatcher)
        b.attach(fake())
        advanceUntilIdle()
        assertEquals("/home/demo", b.listing.value.path)
        assertEquals("no se sabe", b.listing.value.notice)
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


    // ---- ADR-0024: uploads, folder downloads and changes to the destination ----

    private val upload = ByteArray(6 * BLOCK + 77) { (it * 7 % 253).toByte() }

    @Test
    fun uploads_through_a_partial_file_renamed_when_whole() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake()
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("nuevo.bin", upload)))
        advanceUntilIdle()
        val t = b.transfers.value.single()
        assertEquals(SftpTransfer.State.DONE, t.state)
        assertEquals(SftpTransfer.Direction.UPLOAD, t.direction)
        assertEquals("/home/demo/nuevo.bin", t.remotePath)
        assertContentEquals(upload, sftp.files.getValue("/home/demo/nuevo.bin"))
        assertFalse(sftp.files.keys.any { it.endsWith(".titan-part") })
        // The folder on show lists it.
        assertTrue(b.listing.value.entries.any { it.name == "nuevo.bin" })
    }

    @Test
    fun a_dropped_upload_resumes_from_what_the_server_holds_minus_the_writes_in_flight() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply {
            writeFailAfter = 4L * BLOCK
            writeAhead = BLOCK.toLong()
        }
        var lost = 0
        b.attach(sftp) { lost++ }
        advanceUntilIdle()
        val source = BytesSource("nuevo.bin", upload)
        b.upload(listOf(source))
        advanceUntilIdle()
        assertEquals(1, lost)
        assertEquals(SftpTransfer.State.WAITING, b.transfers.value.single().state)

        b.attach(sftp)
        advanceUntilIdle()
        assertEquals(listOf(0L, 3L * BLOCK), source.opened)
        assertEquals(listOf(0L, 3L * BLOCK), sftp.writeOffsets)
        assertEquals(SftpTransfer.State.DONE, b.transfers.value.single().state)
        assertContentEquals(upload, sftp.files.getValue("/home/demo/nuevo.bin"))
    }

    @Test
    fun a_taken_name_is_asked_about_keep_both_numbers_it() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake()
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("datos.bin", upload)))
        advanceUntilIdle()
        val question = assertNotNull(b.conflict.value)
        assertEquals("datos.bin", question.name)
        assertEquals(0, question.remaining)
        assertTrue(b.transfers.value.isEmpty())

        question.answer(ConflictChoice.KEEP_BOTH)
        advanceUntilIdle()
        assertNull(b.conflict.value)
        assertContentEquals(payload, sftp.files.getValue("/home/demo/datos.bin"))
        assertContentEquals(upload, sftp.files.getValue("/home/demo/datos (1).bin"))
        assertEquals("datos (1).bin", b.transfers.value.single().name)
    }

    @Test
    fun replace_overwrites_and_skip_uploads_nothing() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake()
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("datos.bin", upload)))
        advanceUntilIdle()
        b.conflict.value!!.answer(ConflictChoice.SKIP)
        advanceUntilIdle()
        assertTrue(b.transfers.value.isEmpty())
        assertContentEquals(payload, sftp.files.getValue("/home/demo/datos.bin"))

        b.upload(listOf(BytesSource("datos.bin", upload)))
        advanceUntilIdle()
        b.conflict.value!!.answer(ConflictChoice.REPLACE)
        advanceUntilIdle()
        assertContentEquals(upload, sftp.files.getValue("/home/demo/datos.bin"))
        assertEquals(listOf("datos.bin", "proyecto"), sftp.dirs.getValue("/home/demo").sorted())
    }

    @Test
    fun an_answer_for_the_rest_is_not_asked_again() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply {
            files["/home/demo/otro.bin"] = byteArrayOf(1)
            dirs.getValue("/home/demo") += "otro.bin"
        }
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("datos.bin", upload), BytesSource("libre.bin", upload), BytesSource("otro.bin", upload)))
        advanceUntilIdle()
        val question = b.conflict.value!!
        assertEquals(1, question.remaining)
        question.answer(ConflictChoice.REPLACE, forTheRest = true)
        advanceUntilIdle()
        assertNull(b.conflict.value)
        assertEquals(3, b.transfers.value.count { it.state == SftpTransfer.State.DONE })
        assertContentEquals(upload, sftp.files.getValue("/home/demo/otro.bin"))
    }

    @Test
    fun creates_and_renames_and_refuses_a_taken_name() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake()
        b.attach(sftp)
        advanceUntilIdle()
        b.createFolder("  nueva ")
        advanceUntilIdle()
        assertTrue("/home/demo/nueva" in sftp.dirs)
        assertTrue(b.listing.value.entries.any { it.name == "nueva" })

        b.rename(b.file, "renombrado.bin")
        advanceUntilIdle()
        assertContentEquals(payload, sftp.files.getValue("/home/demo/renombrado.bin"))

        val renamed = b.listing.value.entries.first { it.name == "renombrado.bin" }
        b.rename(renamed, "nueva")
        advanceUntilIdle()
        assertEquals(SftpMessage("Ya existe nueva", error = true), b.message.value)
        assertTrue("/home/demo/renombrado.bin" in sftp.files)

        b.createFolder("a/b")
        assertTrue(b.message.value!!.error)
    }

    @Test
    fun moves_entries_and_keeps_a_folder_out_of_itself() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake()
        b.attach(sftp)
        advanceUntilIdle()
        val all = b.listing.value.entries
        b.move(all, "/home/demo/proyecto")
        advanceUntilIdle()
        assertContentEquals(payload, sftp.files.getValue("/home/demo/proyecto/datos.bin"))
        assertTrue("/home/demo/proyecto" in sftp.dirs)
        val message = b.message.value!!
        assertTrue(message.error)
        assertEquals("Movido 1 elemento · proyecto no puede ir dentro de sí misma", message.text)
    }

    private val partial = "/home/demo/.nuevo.bin.titan-part"

    @Test
    fun cancelling_an_upload_deletes_its_partial_file_at_once_when_connected() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply { writeHangAt = 2L * BLOCK }
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("nuevo.bin", upload)))
        advanceUntilIdle()
        assertTrue(sftp.reachedWriteHang.isCompleted)
        assertTrue(partial in sftp.files)

        b.cancel(b.transfers.value.single().id)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.CANCELLED, b.transfers.value.single().state)
        assertFalse(partial in sftp.files)
        assertNull(b.transfers.value.single().error)
    }

    @Test
    fun an_upload_cancelled_without_connection_waits_for_one_to_delete_its_partial_file() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply { writeFailAfter = 3L * BLOCK }
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("nuevo.bin", upload)))
        advanceUntilIdle()
        val id = b.transfers.value.single().id
        assertEquals(SftpTransfer.State.WAITING, b.transfers.value.single().state)

        b.cancel(id)
        advanceUntilIdle()
        // Nothing can be deleted yet: it says so and waits, and closing now would leave the file.
        assertEquals(SftpTransfer.State.CANCELLING, b.transfers.value.single().state)
        assertTrue(partial in sftp.files)
        assertEquals(listOf(partial), b.partialUploads())

        b.attach(sftp)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.CANCELLED, b.transfers.value.single().state)
        assertFalse(partial in sftp.files)
        assertTrue(b.partialUploads().isEmpty())
    }

    @Test
    fun closing_while_connected_deletes_the_partial_uploads_first() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply { writeHangAt = 2L * BLOCK }
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("nuevo.bin", upload)))
        advanceUntilIdle()
        assertTrue(partial in sftp.files)

        b.close()
        assertFalse(partial in sftp.files)
    }

    @Test
    fun closing_without_connection_leaves_what_partial_uploads_said() = runTest {
        val b = browser(MemoryStore())
        val sftp = fake().apply { writeFailAfter = 3L * BLOCK }
        b.attach(sftp)
        advanceUntilIdle()
        b.upload(listOf(BytesSource("nuevo.bin", upload)))
        advanceUntilIdle()
        assertEquals(listOf(partial), b.partialUploads())

        b.close()
        assertTrue(partial in sftp.files)
    }

    private fun tree() = FakeSftp(
        files = mapOf(
            "/home/demo/proyecto/a.txt" to byteArrayOf(1, 2, 3),
            "/home/demo/proyecto/src/b.txt" to ByteArray(2 * BLOCK) { 9 },
            "/home/demo/otro/c.txt" to byteArrayOf(4),
        ),
        dirs = mapOf(
            "/home/demo" to listOf("proyecto", "otro"),
            "/home/demo/proyecto" to listOf("a.txt", "src", "vacia", "enlace"),
            "/home/demo/proyecto/src" to listOf("b.txt"),
            "/home/demo/proyecto/vacia" to emptyList(),
            "/home/demo/otro" to listOf("c.txt"),
        ),
        links = mapOf("/home/demo/proyecto/enlace" to "/home/demo/otro"),
    )

    @Test
    fun deleting_a_folder_takes_its_content_but_never_follows_a_link() = runTest {
        val b = browser(MemoryStore())
        val sftp = tree()
        b.attach(sftp)
        advanceUntilIdle()
        val proyecto = b.listing.value.entries.first { it.name == "proyecto" }

        val contents = async { b.contentsOf(listOf(proyecto)) }
        advanceUntilIdle()
        // a.txt, b.txt and the link; src and vacia.
        assertEquals(SftpContents(files = 3, folders = 2, bytes = 3L + 2 * BLOCK), contents.await())

        b.delete(listOf(proyecto))
        advanceUntilIdle()
        assertEquals(listOf("otro"), sftp.dirs.getValue("/home/demo"))
        assertFalse(sftp.files.keys.any { it.startsWith("/home/demo/proyecto") })
        assertContentEquals(byteArrayOf(4), sftp.files.getValue("/home/demo/otro/c.txt"))
        assertFalse(b.message.value!!.error)
    }

    @Test
    fun downloads_a_folder_as_a_folder_keeping_its_layout() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        b.attach(tree())
        advanceUntilIdle()
        b.download(b.listing.value.entries.first { it.name == "proyecto" })
        advanceUntilIdle()
        val t = b.transfers.value.single()
        assertEquals(SftpTransfer.State.DONE, t.state)
        assertTrue(t.folder)
        assertEquals(2, t.files)
        assertEquals(2, t.filesDone)
        // The link to another folder is left out.
        assertEquals(1, t.skipped)
        assertEquals(3L + 2 * BLOCK, t.transferred)
        assertEquals("Descargas/proyecto", t.location)
        assertContentEquals(byteArrayOf(1, 2, 3), store.saved.getValue("proyecto/a.txt"))
        assertContentEquals(ByteArray(2 * BLOCK) { 9 }, store.saved.getValue("proyecto/src/b.txt"))
        assertTrue("proyecto/vacia" in store.folders)
    }

    @Test
    fun cancelling_a_folder_download_deletes_what_it_wrote() = runTest {
        val store = MemoryStore()
        val b = browser(store)
        val sftp = tree()
        b.attach(sftp)
        advanceUntilIdle()
        // The second file hangs: the first one is already saved.
        sftp.hangAt = BLOCK.toLong()
        b.download(b.listing.value.entries.first { it.name == "proyecto" })
        advanceUntilIdle()
        assertTrue(sftp.reachedHang.isCompleted)
        b.cancel(b.transfers.value.single().id)
        advanceUntilIdle()
        assertEquals(SftpTransfer.State.CANCELLED, b.transfers.value.single().state)
        assertTrue(store.saved.isEmpty())
        assertEquals(setOf("proyecto/a.txt", "proyecto/src/b.txt"), store.discarded.toSet())
        assertEquals(listOf("proyecto"), store.discardedFolders)
    }

    private companion object {
        const val BLOCK = 1024
    }
}
