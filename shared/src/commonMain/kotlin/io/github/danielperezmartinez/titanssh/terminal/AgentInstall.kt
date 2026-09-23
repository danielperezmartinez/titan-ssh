package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession

/**
 * Pure helpers for installing the resilience level-3 agent (`titan-agent`) on a
 * destination (ADR-0008 §5). The platform-specific installer that actually talks
 * to the host lives in `jvmShared` (`AgentInstaller`); the OS/arch mapping, the
 * versioned remote path and the checksum-line parsing are here so they can be
 * unit-tested headless.
 */

/**
 * Ensures the level-3 agent is installed on [SshSession]'s destination and
 * returns its remote path, or null to **degrade** (no binary bundled for the
 * arch, or the install/verify failed). Implemented in `jvmShared` on top of
 * `AgentInstaller`; injected into [SessionManager]/[SessionTab] so `commonMain`
 * stays free of platform code. Absent (null deployer) ⇒ `AGENT` sessions behave
 * exactly as level 2/1 today.
 */
fun interface AgentDeployer {
    suspend fun ensureInstalled(session: SshSession): String?
}

/** The destination's OS/arch, normalized to the names used for agent binaries. */
data class AgentTarget(val os: String, val arch: String) {
    /** e.g. `linux-amd64`, used in the binary name and the resource lookup. */
    val slug: String get() = "$os-$arch"
}

object AgentInstall {

    /** Default per-user install directory on the destination (user space, no root). */
    const val DEFAULT_BASE_DIR: String = "~/.local/share/titan-ssh"

    /**
     * Maps `uname -s` / `uname -m` to an [AgentTarget], or null if unsupported.
     * Accepts the common aliases (`x86_64`/`amd64`, `aarch64`/`arm64`).
     */
    fun parseUname(sysname: String, machine: String): AgentTarget? {
        val os = when (sysname.trim().lowercase()) {
            "linux" -> "linux"
            "darwin" -> "darwin"
            else -> return null
        }
        val arch = when (machine.trim().lowercase()) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return AgentTarget(os, arch)
    }

    /**
     * Versioned remote path for the agent binary. Versioning the filename lets
     * versions coexist and makes install idempotent (skip if the checksum
     * already matches). No shell metacharacters, so callers can leave it unquoted
     * (which also lets a leading `~` expand).
     */
    fun remotePath(baseDir: String, version: String, target: AgentTarget): String =
        "$baseDir/agent-$version-${target.slug}"

    /**
     * Extracts the hex digest from a `sha256sum` line (`<hex>  <path>`), or null
     * if the output has no digest (e.g. the file was absent). Case-insensitive,
     * returned lowercased.
     */
    fun parseSha256(output: String): String? {
        val token = output.trim().substringBefore(' ').trim()
        val hex = token.lowercase()
        return if (hex.length == 64 && hex.all { it in "0123456789abcdef" }) hex else null
    }
}
