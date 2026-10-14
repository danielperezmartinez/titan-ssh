package io.github.danielperezmartinez.titanssh.sftp

import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream

actual fun createDownloadStore(): DownloadStore = FileDownloadStore(desktopDownloadsDir())

/**
 * The user's Downloads folder: on Linux the XDG one (`Descargas` on a Spanish
 * desktop), elsewhere `~/Downloads`. Falls back to the home folder.
 */
internal fun desktopDownloadsDir(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (!os.contains("win") && !os.contains("mac")) {
        xdgDownloadDir(home)?.let { return it }
    }
    return File(home, "Downloads").takeIf { it.isDirectory } ?: home
}

/** `XDG_DOWNLOAD_DIR` from the environment or `~/.config/user-dirs.dirs`, if it exists. */
private fun xdgDownloadDir(home: File): File? {
    val configured = System.getenv("XDG_DOWNLOAD_DIR")
        ?: runCatching {
            val configHome = System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
            File(configHome, "user-dirs.dirs").readLines()
                .firstOrNull { it.trim().startsWith("XDG_DOWNLOAD_DIR=") }
                ?.substringAfter('=')?.trim()?.trim('"')
        }.getOrNull()
        ?: return null
    val path = configured.replace("\$HOME", home.path)
    return File(path).takeIf { it.isDirectory }
}

/** Downloads as plain files in [directory]. */
internal class FileDownloadStore(private val directory: File) : DownloadStore {
    override fun create(fileName: String): DownloadTarget {
        directory.mkdirs()
        var n = 0
        while (true) {
            val candidate = File(directory, if (n == 0) fileName else numberedFileName(fileName, n))
            // createNewFile is atomic: two downloads of the same name never share a file.
            if (candidate.createNewFile()) return FileDownloadTarget(candidate)
            n++
        }
    }
}

private class FileDownloadTarget(private val file: File) : DownloadTarget {
    private val out = FileOutputStream(file)

    override val name: String get() = file.name
    override val location: String get() = file.path

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun complete() = out.close()

    override fun discard() {
        runCatching { out.close() }
        file.delete()
    }

    override fun open(): Boolean {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return runCatching { Desktop.getDesktop().open(file) }.isSuccess
        }
        return runCatching { ProcessBuilder("xdg-open", file.path).start() }.isSuccess
    }
}
