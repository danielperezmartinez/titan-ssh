package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshSession

/**
 * Pure helpers for installing the resilience level-3 agent (`titan-agent`) on a
 * destination (ADR-0008 §5, ADR-0009 §6–§7). The platform-specific installer
 * that actually talks to the host lives in `jvmShared` (`AgentInstaller`); the
 * OS/arch mapping, the per-OS paths, the shell quoting of the launch command and
 * the checksum parsing are here so they can be unit-tested headless.
 */

/**
 * Ensures the level-3 agent is installed on [SshSession]'s destination and
 * returns how to launch it, or why the tab must **degrade** (no binary for the
 * OS/arch, or the install/verify failed), as an [AgentDeployment]. Implemented
 * in `jvmShared` on top of `AgentInstaller`; injected into
 * [SessionManager]/[SessionTab] so `commonMain` stays free of platform code.
 * Absent (null deployer) ⇒ `AGENT` sessions behave exactly as level 2/1.
 */
fun interface AgentDeployer {
    suspend fun ensureInstalled(session: SshSession): AgentDeployment
}

/** The destination's OS/arch, normalized to Go's `GOOS`/`GOARCH` names. */
data class AgentTarget(val os: String, val arch: String) {
    /** e.g. `linux-amd64`, used in the binary names and the resource lookup. */
    val slug: String get() = "$os-$arch"

    val isWindows: Boolean get() = os == "windows"

    /** `.exe` on Windows, which will not run a file without it; empty elsewhere. */
    val executableSuffix: String get() = if (isWindows) ".exe" else ""

    /** Name of the build output and of the bundled resource: `titan-agent-<slug>[.exe]`. */
    val fileName: String get() = "titan-agent-$slug$executableSuffix"
}

/**
 * The shell the destination's `sshd` runs `exec` commands with. It decides how
 * the agent's path must be quoted (Win32-OpenSSH uses its `DefaultShell`,
 * `cmd.exe` unless configured otherwise).
 */
enum class RemoteShell { POSIX, CMD, POWERSHELL }

/** An installed agent: its path as the destination's shell sees it, and that shell. */
data class AgentLaunch(val path: String, val shell: RemoteShell) {
    /** The `exec` command that runs the agent with [args] (shell-safe tokens only). */
    fun command(vararg args: String): String = AgentInstall.command(shell, path, args.toList())
}

/** What the Windows probe learned: the target and the per-user `%LOCALAPPDATA%`. */
data class WindowsProbe(val target: AgentTarget?, val localAppData: String)

object AgentInstall {

    /**
     * Install directory on a Unix destination when its home is unknown (no SFTP):
     * left to the shell, which expands the `~`.
     */
    const val DEFAULT_BASE_DIR: String = "~/.local/share/titan-ssh"

    /** Install directory under the Unix home (user space, no root). */
    const val UNIX_INSTALL_SUBDIR: String = ".local/share/titan-ssh"

    /**
     * Install directory under `%LOCALAPPDATA%`. It is also the agent's state
     * directory (`agent.lock`, `agent.json`), so cleanup only touches `agent-*`.
     */
    const val WINDOWS_INSTALL_SUBDIR: String = "titan-ssh"

    /**
     * Echoes the architecture and `%LOCALAPPDATA%` on Windows. Under `cmd.exe` the
     * variables expand; PowerShell prints them literally, which also tells the
     * two shells apart (see [parseWindowsProbe]). No quotes, so it reads the same
     * whether or not it is wrapped in `cmd /c`.
     */
    const val WINDOWS_PROBE: String =
        "echo %PROCESSOR_ARCHITECTURE% %PROCESSOR_ARCHITEW6432% %LOCALAPPDATA%"

    /**
     * Maps `uname -s` / `uname -m` to an [AgentTarget], or null if unsupported.
     * Covers the Unix targets of ADR-0009 §6 and the usual `uname` aliases.
     */
    fun parseUname(sysname: String, machine: String): AgentTarget? {
        val os = when (sysname.trim().lowercase()) {
            "linux" -> "linux"
            "darwin" -> "darwin"
            "freebsd" -> "freebsd"
            else -> return null
        }
        val m = machine.trim().lowercase()
        val arch = when {
            m == "x86_64" || m == "amd64" -> "amd64"
            m == "aarch64" || m == "arm64" -> "arm64"
            m.startsWith("armv7") || m == "armv8l" || m == "arm" -> "arm"
            m == "i386" || m == "i486" || m == "i586" || m == "i686" || m == "x86" -> "386"
            m == "riscv64" -> "riscv64"
            m == "ppc64le" -> "ppc64le"
            m == "s390x" -> "s390x"
            else -> return null
        }
        val target = AgentTarget(os, arch)
        return target.takeIf { it in SUPPORTED_TARGETS }
    }

    /**
     * Parses the output of [WINDOWS_PROBE]. Returns null when the variables did
     * not expand (the shell is not `cmd.exe`: rerun it through `cmd /c`). A null
     * [WindowsProbe.target] means an unsupported architecture (32-bit Windows).
     */
    fun parseWindowsProbe(output: String): WindowsProbe? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.split(Regex("\\s+"), limit = 3)
        if (parts.size < 3 || parts[0].startsWith("%") || parts[2].startsWith("%")) return null
        // A 32-bit process on 64-bit Windows sees x86 plus the real one in W6432.
        val arch = parts[1].takeUnless { it.startsWith("%") } ?: parts[0]
        val target = when (arch.uppercase()) {
            "AMD64" -> AgentTarget("windows", "amd64")
            "ARM64" -> AgentTarget("windows", "arm64")
            else -> null
        }
        return WindowsProbe(target, parts[2].trimEnd('\\'))
    }

    /** True for an SFTP path with a drive letter (`/C:/Users/u`): the server is Windows. */
    fun isWindowsSftpPath(path: String): Boolean = WINDOWS_SFTP_PATH.containsMatchIn(path)

    /** `C:\Users\u\AppData\Local` → `/C:/Users/u/AppData/Local`, the form SFTP expects. */
    fun windowsToSftpPath(path: String): String = "/" + path.replace('\\', '/').trimEnd('/')

    /** File name of the installed agent: `agent-<version>-<slug>[.exe]`. */
    fun binaryName(version: String, target: AgentTarget): String =
        "agent-$version-${target.slug}${target.executableSuffix}"

    /**
     * Versioned remote path for the agent binary under [baseDir], joined with
     * [separator]. Versioning the filename lets versions coexist and makes the
     * install idempotent (skip if the checksum already matches).
     */
    fun remotePath(baseDir: String, version: String, target: AgentTarget, separator: Char = '/'): String =
        "$baseDir$separator${binaryName(version, target)}"

    /**
     * True for a file in the install directory left by another version: an
     * `agent-*` binary (or an interrupted upload) whose version is not [version].
     * The agent's own `agent.lock`/`agent.json` never match.
     */
    fun isStaleBinary(name: String, version: String): Boolean {
        if (!name.startsWith("agent-")) return false
        // `agent-0.1.0-beta.2-…` shares the `agent-0.1.0-` prefix with 0.1.0, so
        // what follows the version must be the target itself.
        val rest = name.removePrefix("agent-$version-").takeIf { it.length < name.length } ?: return true
        return !CURRENT_VERSION_TAIL.matches(rest)
    }

    /**
     * The `exec` command that runs the agent at [path] with [args], quoted for
     * [shell]. The args must be shell-safe tokens (the session id is sanitized).
     */
    fun command(shell: RemoteShell, path: String, args: List<String>): String {
        val tail = if (args.isEmpty()) "" else " " + args.joinToString(" ")
        return when (shell) {
            RemoteShell.POSIX -> posixQuote(path) + tail
            // Win32-OpenSSH runs `cmd.exe /c "<command>"`: cmd strips that outer
            // pair and keeps the path's own quotes, even with spaces,
            // parentheses or `&` in it (checked against OpenSSH_for_Windows_10.0p2).
            RemoteShell.CMD -> "\"$path\"$tail"
            RemoteShell.POWERSHELL -> "& '${path.replace("'", "''")}'$tail"
        }
    }

    /**
     * A command that prints the SHA-256 of [path], or prints nothing useful when
     * the file is missing or no tool is available (the caller then falls back to
     * reading the file back over SFTP). Parsed by [parseSha256].
     */
    fun hashCommand(shell: RemoteShell, path: String): String = when (shell) {
        // GNU coreutils, then macOS, then FreeBSD.
        RemoteShell.POSIX -> {
            val p = posixQuote(path)
            "sha256sum $p 2>/dev/null || shasum -a 256 $p 2>/dev/null || sha256 -q $p 2>/dev/null"
        }
        RemoteShell.CMD -> "certutil -hashfile \"$path\" SHA256"
        RemoteShell.POWERSHELL -> "certutil -hashfile '${path.replace("'", "''")}' SHA256"
    }

    /**
     * Extracts a SHA-256 hex digest from `sha256sum`/`shasum` (`<hex>  <path>`),
     * `sha256 -q` (`<hex>`) or `certutil -hashfile` output (the digest on its own
     * line, spaced in pairs on old Windows), or null if there is none (e.g. the
     * file was absent). Returned lowercased.
     */
    fun parseSha256(output: String): String? {
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val first = line.substringBefore(' ').lowercase()
            if (first.isSha256Hex()) return first
            if (line.all { it == ' ' || it.isHexDigit() }) {
                val joined = line.replace(" ", "").lowercase()
                if (joined.isSha256Hex()) return joined
            }
        }
        return null
    }

    /**
     * Single-quotes [path] for `sh`, except a leading `~/`, which stays outside
     * the quotes so the shell still expands it.
     */
    fun posixQuote(path: String): String {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        return if (path.startsWith("~/")) "~/" + quote(path.removePrefix("~/")) else quote(path)
    }

    /**
     * Every target the build compiles (ADR-0009 §6). Which of them are bundled in
     * the app and which are downloaded on demand is decided by the build
     * (ADR-0010); detection only needs to know that a binary exists.
     */
    val SUPPORTED_TARGETS: Set<AgentTarget> = setOf(
        AgentTarget("linux", "amd64"),
        AgentTarget("linux", "arm64"),
        AgentTarget("linux", "arm"),
        AgentTarget("linux", "386"),
        AgentTarget("linux", "riscv64"),
        AgentTarget("linux", "ppc64le"),
        AgentTarget("linux", "s390x"),
        AgentTarget("darwin", "amd64"),
        AgentTarget("darwin", "arm64"),
        AgentTarget("windows", "amd64"),
        AgentTarget("windows", "arm64"),
        AgentTarget("freebsd", "amd64"),
        AgentTarget("freebsd", "arm64"),
    )

    private val WINDOWS_SFTP_PATH = Regex("^/[A-Za-z]:(/|$)")

    /** `<os>-<arch>[.exe]`, optionally an interrupted upload (`.tmp.<suffix>`). */
    private val CURRENT_VERSION_TAIL = Regex("^(linux|darwin|windows|freebsd)-[a-z0-9]+(\\.exe)?(\\.tmp\\..+)?$")

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun String.isSha256Hex(): Boolean = length == 64 && all { it.isHexDigit() }
}
