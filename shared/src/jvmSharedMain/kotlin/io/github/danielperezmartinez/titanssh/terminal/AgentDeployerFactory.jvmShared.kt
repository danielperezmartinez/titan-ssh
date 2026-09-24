package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.BuildInfo

/**
 * Bundled `titan-agent` binaries, cross-compiled by the `buildAgentBinaries`
 * Gradle task into JVM resources under `/agent/` and loaded here via the
 * classloader (works for both the desktop app and the Android app, since both
 * package `jvmShared` resources). [VERSION] is the single app version, which
 * the build also stamps into the binary, and drives the versioned install path
 * (ADR-0008 §5).
 */
object AgentBinaries {
    const val VERSION: String = BuildInfo.VERSION

    /** Bytes of the bundled agent for [target], or null if none is packaged. */
    fun load(target: AgentTarget): ByteArray? =
        AgentBinaries::class.java.getResourceAsStream("/agent/titan-agent-${target.slug}")
            ?.use { it.readBytes() }
}

/**
 * Builds the deployer over [AgentInstaller] with the bundled binaries. Non-null:
 * when nothing is bundled for the detected target it simply yields no path and
 * the tab degrades to level 2/1.
 */
actual fun createAgentDeployer(): AgentDeployer? =
    agentDeployer(version = AgentBinaries.VERSION) { target -> AgentBinaries.load(target) }
