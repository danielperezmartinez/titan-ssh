package im.gar.titanssh.ssh

import java.io.File

/**
 * Desktop [KnownHostsStore]: a `known_hosts` file under the same per-user config
 * directory the config store uses (Windows `%APPDATA%`, otherwise
 * `$XDG_CONFIG_HOME` or `~/.config`), in the `titan-ssh` subfolder.
 */
actual fun createKnownHostsStore(): KnownHostsStore =
    FileKnownHostsStore(File(desktopKnownHostsDirectory(), "known_hosts"))

private fun desktopKnownHostsDirectory(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val base: File = if (os.contains("win")) {
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
    } else {
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
    }
    return File(base, "titan-ssh")
}
