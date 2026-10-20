package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.AppChannel
import java.io.File

/**
 * Desktop deployer: bundled binaries, and downloads cached in the per-user cache
 * directory (Windows `%LOCALAPPDATA%`, otherwise `$XDG_CACHE_HOME` or
 * `~/.cache`), under `<app>/cache/agent` ([AppChannel.appName]).
 */
actual fun createAgentDeployer(): AgentDeployer? = bundledAgentDeployer(desktopAgentCacheDirectory())

private fun desktopAgentCacheDirectory(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val base: File = if (os.contains("win")) {
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
    } else {
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache")
    }
    return File(base, "${AppChannel.appName}/cache/agent")
}
