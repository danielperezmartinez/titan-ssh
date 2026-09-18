package im.gar.titanssh.config

import java.io.File

/**
 * Desktop [ConfigStore]: stores the JSON config under the OS-appropriate
 * per-user config directory (Windows `%APPDATA%`, otherwise `$XDG_CONFIG_HOME`
 * or `~/.config`), in a `titan-ssh` subfolder.
 */
actual fun createConfigStore(): ConfigStore =
    JsonFileConfigStore(desktopConfigDirectory())

private fun desktopConfigDirectory(): File {
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
