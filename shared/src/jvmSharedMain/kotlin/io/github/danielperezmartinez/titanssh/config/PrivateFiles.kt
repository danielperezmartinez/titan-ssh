package io.github.danielperezmartinez.titanssh.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Owner-only modes for the app's data files: `0700` directories and `0600`
 * files, on file systems with POSIX permissions (Linux, macOS, Android), where
 * the process umask would otherwise decide. Elsewhere (Windows) only the plain
 * file operation runs: the per-user profile's ACL already keeps other users out.
 */
internal object PrivateFiles {

    private val DIRECTORY = PosixFilePermissions.fromString("rwx------")
    private val FILE = PosixFilePermissions.fromString("rw-------")

    /**
     * Creates [directory] if needed and makes it owner-only, together with the
     * regular files directly inside it, so files written earlier with the
     * umask's mode are fixed too.
     */
    fun secureDirectory(directory: File) {
        directory.mkdirs()
        val path = directory.toPath()
        if (!isPosix(path)) return
        setMode(path, DIRECTORY)
        directory.listFiles()?.filter { it.isFile }?.forEach { setMode(it.toPath(), FILE) }
    }

    /**
     * Writes [text] to [file], replacing it. The file is created anew and
     * owner-only before anything is written to it.
     */
    fun writeText(file: File, text: String) {
        create(file)
        file.writeText(text, Charsets.UTF_8)
    }

    /** Appends [text] to [file], creating it owner-only first if it is missing. */
    fun appendText(file: File, text: String) {
        if (!file.exists()) create(file)
        file.appendText(text, Charsets.UTF_8)
    }

    /** Copies [source] to a new owner-only [target], replacing it. */
    fun copy(source: File, target: File) {
        create(target)
        target.writeBytes(source.readBytes())
    }

    private fun create(file: File) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        Files.deleteIfExists(path)
        if (isPosix(path)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE))
        } else {
            Files.createFile(path)
        }
    }

    private fun isPosix(path: Path): Boolean =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null

    private fun setMode(path: Path, mode: Set<PosixFilePermission>) {
        // A file another user owns cannot be changed; leaving it as it is beats
        // failing the read or write the caller is about to do.
        runCatching { Files.setPosixFilePermissions(path, mode) }
    }
}
