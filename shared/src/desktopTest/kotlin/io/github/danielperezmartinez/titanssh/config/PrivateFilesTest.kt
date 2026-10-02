package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.ssh.FileKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostEntry
import io.github.danielperezmartinez.titanssh.terminal.AgentWatchState
import io.github.danielperezmartinez.titanssh.terminal.JsonFileAgentWatchStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PrivateFiles] and the stores that write through it, on a POSIX file system.
 * Elsewhere (Windows) there are no modes to check and each test returns early;
 * run them on Linux to exercise them.
 */
class PrivateFilesTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "titan-private-test-${System.nanoTime()}")

    private fun posix(dir: File): Boolean {
        dir.mkdirs()
        return Files.getFileAttributeView(dir.toPath(), PosixFileAttributeView::class.java) != null
    }

    private fun mode(file: File): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private fun loosen(file: File, mode: String) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
    }

    @Test
    fun secure_directory_fixes_the_folder_and_the_files_already_in_it() {
        val dir = tempDir()
        try {
            if (!posix(dir)) return
            val existing = File(dir, "config.json").apply { writeText("{}") }
            loosen(dir, "rwxr-xr-x")
            loosen(existing, "rw-r--r--")

            PrivateFiles.secureDirectory(dir)

            assertEquals("rwx------", mode(dir))
            assertEquals("rw-------", mode(existing))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun written_files_are_owner_only_even_when_they_existed_with_another_mode() {
        val dir = tempDir()
        try {
            if (!posix(dir)) return
            val fresh = File(dir, "fresh.txt")
            val replaced = File(dir, "replaced.txt").apply { writeText("old") }
            loosen(replaced, "rw-rw-rw-")
            val appended = File(dir, "appended.txt")
            val copied = File(dir, "copied.txt")

            PrivateFiles.writeText(fresh, "a")
            PrivateFiles.writeText(replaced, "new")
            PrivateFiles.appendText(appended, "line\n")
            PrivateFiles.copy(replaced, copied)

            listOf(fresh, replaced, appended, copied).forEach { assertEquals("rw-------", mode(it), it.name) }
            assertEquals("new", replaced.readText())
            assertEquals("new", copied.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun the_stores_leave_their_files_owner_only() = runBlocking {
        val dir = tempDir()
        try {
            if (!posix(dir)) return@runBlocking
            JsonFileConfigStore(dir).save(TitanConfig())
            val knownHosts = FileKnownHostsStore(File(dir, "known_hosts"))
            knownHosts.add(KnownHostEntry("example.net", 22, "ssh-ed25519", "AAAA"))
            val replacedHosts = FileKnownHostsStore(File(dir, "known_hosts_replaced"))
            replacedHosts.replace(KnownHostEntry("example.net", 2222, "ssh-ed25519", "BBBB"))
            JsonFileAgentWatchStore(File(dir, JsonFileAgentWatchStore.FILE_NAME)).save(AgentWatchState())

            listOf(JsonFileConfigStore.CONFIG_FILE, "known_hosts", "known_hosts_replaced", JsonFileAgentWatchStore.FILE_NAME)
                .forEach { assertEquals("rw-------", mode(File(dir, it)), it) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
