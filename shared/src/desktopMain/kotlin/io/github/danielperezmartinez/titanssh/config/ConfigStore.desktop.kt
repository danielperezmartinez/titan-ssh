package io.github.danielperezmartinez.titanssh.config

import io.github.danielperezmartinez.titanssh.AppChannel
import java.io.File

/**
 * Desktop [ConfigStore]: stores the JSON config under the OS-appropriate
 * per-user config directory (Windows `%APPDATA%`, otherwise `$XDG_CONFIG_HOME`
 * or `~/.config`), in a [AppChannel.appName] subfolder.
 */
actual fun createConfigStore(): ConfigStore =
    JsonFileConfigStore(desktopConfigDirectory())

/**
 * The per-user `titan-ssh` (or `titan-ssh-canary`) folder holding the config,
 * `known_hosts` and the agents file. Made owner-only the first time it is
 * resolved ([PrivateFiles]).
 */
internal fun desktopConfigDirectory(): File = configDirectory

/** The stable app's folder, which the canary copies from but never writes (ADR-0027 §4). */
internal fun stableConfigDirectory(): File = File(configBase, AppChannel.STABLE_APP_NAME)

private val configDirectory: File by lazy {
    File(configBase, AppChannel.appName).also(PrivateFiles::secureDirectory)
}

private val configBase: File by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (os.contains("win")) {
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
    } else {
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
    }
}
