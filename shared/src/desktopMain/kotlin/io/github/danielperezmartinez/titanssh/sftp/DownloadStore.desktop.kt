package io.github.danielperezmartinez.titanssh.sftp

import java.awt.Desktop
import java.io.File

actual fun createDownloadStore(): DownloadStore = FileDownloadStore(desktopDownloadsDir(), ::openWithSystem)

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

/** Opens a file or folder with the desktop's app for it. */
private fun openWithSystem(file: File): Boolean {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        return runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }
    return runCatching { ProcessBuilder("xdg-open", file.path).start() }.isSuccess
}
