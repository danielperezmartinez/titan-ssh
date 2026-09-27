package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.AndroidConfigContext
import java.io.File

/**
 * Android deployer: bundled binaries, and downloads cached in the app's
 * `cacheDir`, which the system may clear (they are fetched again when needed).
 */
actual fun createAgentDeployer(): AgentDeployer? =
    bundledAgentDeployer(runCatching { File(AndroidConfigContext.require().cacheDir, "agent") }.getOrNull())
