package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.BuildInfo
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch

/**
 * Installs the level-3 agent binary on a destination (ADR-0008 §5, ADR-0009 §7),
 * on Unix and Windows alike.
 *
 * Flow:
 * 1. **Detect** the host. Over SFTP, the canonical login directory tells the OS
 *    apart (`/C:/Users/u` is Windows). Windows then reports its architecture,
 *    `%LOCALAPPDATA%` and its `exec` shell (cmd or PowerShell) in one probe;
 *    Unix runs `uname`. Without SFTP the host is taken as Unix.
 * 2. **Skip** the upload when the versioned file already has the expected
 *    SHA-256: a remote hash tool answers cheaply (so a reconnect stays fast),
 *    and reading the file back over SFTP covers hosts without one.
 * 3. **Upload** over SFTP to a unique temporary name in the same directory,
 *    `chmod 0700` on Unix, and rename it into place. Without SFTP (Unix only),
 *    pipe the exact byte count through `head -c`.
 * 4. **Verify** the installed file's SHA-256 again, then remove the binaries of
 *    other versions (on Windows a running one cannot be deleted: it is skipped
 *    and retried on the next install).
 *
 * Everything lives in user space, never root. On any failure the caller
 * degrades to resilience level 2/1.
 */
class AgentInstaller(
    private val session: SshSession,
    private val version: String = BuildInfo.VERSION,
    /** Which files in the install directory to remove after a good install. */
    private val isStale: (fileName: String) -> Boolean = { AgentInstall.isStaleBinary(it, version) },
) {

    /** Outcome of [ensureInstalled]. */
    sealed interface Result {
        /** The binary is in place; [uploaded] is false when it was already there. */
        data class Installed(val launch: AgentLaunch, val target: AgentTarget, val uploaded: Boolean) : Result {
            val path: String get() = launch.path
        }
        /** The destination's OS/arch has no agent binary, or it could not be detected. */
        data class Unsupported(val reason: String) : Result
        /** The upload or its verification failed. */
        data class Failed(val reason: String) : Result
    }

    /**
     * Where and how the agent goes on the detected host: [installDir] and
     * [separator] as its shell sees them, [sftpDir] the same directory for SFTP
     * (null without SFTP).
     */
    private data class Host(
        val target: AgentTarget,
        val shell: RemoteShell,
        val installDir: String,
        val separator: Char,
        val sftpDir: String?,
    )

    /**
     * Ensures the agent binary for the destination is installed and returns how
     * to launch it. [binaryFor] supplies the bytes for a target (bundled, or
     * downloaded and checked against the checksum pinned in the app), or null if
     * there is none for it.
     */
    suspend fun ensureInstalled(binaryFor: suspend (AgentTarget) -> ByteArray?): Result {
        val sftp = runCatching { session.openSftp() }.getOrNull()
        try {
            val host = detect(sftp) ?: return Result.Unsupported("could not detect a supported OS/arch")
            val bytes = binaryFor(host.target)
                ?: return Result.Unsupported("no agent binary available for ${host.target.slug}")
            return install(host, sftp, bytes)
        } finally {
            runCatching { sftp?.close() }
        }
    }

    /** Detects the destination's OS/arch (see the class doc); null if unsupported. */
    suspend fun detectTarget(): AgentTarget? {
        val sftp = runCatching { session.openSftp() }.getOrNull()
        try {
            return detect(sftp)?.target
        } finally {
            runCatching { sftp?.close() }
        }
    }

    private suspend fun detect(sftp: SshSftp?): Host? {
        val home = sftp?.let { runCatching { it.canonicalize(".") }.getOrNull() }
        if (home != null && AgentInstall.isWindowsSftpPath(home)) return detectWindows()
        val target = detectUnix() ?: return null
        return if (home != null) {
            val dir = "${home.trimEnd('/')}/${AgentInstall.UNIX_INSTALL_SUBDIR}"
            Host(target, RemoteShell.POSIX, dir, '/', dir)
        } else {
            Host(target, RemoteShell.POSIX, AgentInstall.DEFAULT_BASE_DIR, '/', null)
        }
    }

    private suspend fun detectUnix(): AgentTarget? {
        val lines = runCommand("uname -s; uname -m").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        return AgentInstall.parseUname(lines[0], lines[1])
    }

    private suspend fun detectWindows(): Host? {
        // cmd.exe expands the variables; any other shell (PowerShell) prints
        // them as they are, so the probe is repeated through cmd explicitly.
        var shell = RemoteShell.CMD
        var probe = AgentInstall.parseWindowsProbe(runCommand(AgentInstall.WINDOWS_PROBE))
        if (probe == null) {
            shell = RemoteShell.POWERSHELL
            probe = AgentInstall.parseWindowsProbe(runCommand("cmd /c ${AgentInstall.WINDOWS_PROBE}"))
        }
        val target = probe?.target ?: return null
        val dir = "${probe.localAppData}\\${AgentInstall.WINDOWS_INSTALL_SUBDIR}"
        return Host(target, shell, dir, '\\', AgentInstall.windowsToSftpPath(dir))
    }

    private suspend fun install(host: Host, sftp: SshSftp?, bytes: ByteArray): Result {
        val expected = sha256Hex(bytes)
        val name = AgentInstall.binaryName(version, host.target)
        val path = "${host.installDir}${host.separator}$name"
        val sftpPath = host.sftpDir?.let { "$it/$name" }
        val launch = AgentLaunch(path, host.shell)

        if (remoteSha256(host, sftp, path, sftpPath, bytes.size.toLong()) == expected) {
            removeStale(host, sftp)
            return Result.Installed(launch, host.target, uploaded = false)
        }

        if (sftp != null && sftpPath != null) {
            val dir = sftpPath.substringBeforeLast('/')
            val tmp = "$dir/$name.tmp.${randomSuffix()}"
            try {
                sftp.mkdirs(dir)
                sftp.write(tmp, bytes)
                if (!host.target.isWindows) sftp.chmod(tmp, MODE_0700)
                // SFTP v3 will not rename over a file: clear a bad copy first.
                if (sftp.size(sftpPath) != null) sftp.remove(sftpPath)
                sftp.rename(tmp, sftpPath)
            } catch (e: Exception) {
                runCatching { sftp.remove(tmp) }
                return Result.Failed("upload failed: ${e.message ?: e::class.simpleName}")
            }
        } else {
            // Unix without SFTP: stream the bytes through the exec channel.
            val q = AgentInstall.posixQuote(path)
            val qTmp = AgentInstall.posixQuote("$path.tmp.${randomSuffix()}")
            runCommand(
                "mkdir -p ${AgentInstall.posixQuote(host.installDir)} && head -c ${bytes.size} > $qTmp && " +
                    "chmod 700 $qTmp && mv -f $qTmp $q",
                stdin = bytes,
            )
        }

        val got = remoteSha256(host, sftp, path, sftpPath, bytes.size.toLong())
        if (got != expected) {
            return Result.Failed("checksum mismatch after upload (expected $expected, got ${got ?: "none"})")
        }
        removeStale(host, sftp)
        return Result.Installed(launch, host.target, uploaded = true)
    }

    /**
     * SHA-256 of the installed file, or null if it is missing. Asks the host's
     * own tool first; if that gives nothing, reads the file back over SFTP, but
     * only when its size matches (a different size cannot be the right file).
     */
    private suspend fun remoteSha256(
        host: Host,
        sftp: SshSftp?,
        path: String,
        sftpPath: String?,
        expectedSize: Long,
    ): String? {
        AgentInstall.parseSha256(runCommand(AgentInstall.hashCommand(host.shell, path)))?.let { return it }
        if (sftp == null || sftpPath == null) return null
        return runCatching {
            if (sftp.size(sftpPath) != expectedSize) null else sha256Hex(sftp.read(sftpPath))
        }.getOrNull()
    }

    /**
     * Removes the binaries of other versions, best-effort. On Unix a daemon still
     * running one keeps working (the inode lives until it exits); on Windows a
     * running `.exe` cannot be deleted and is simply left for the next install.
     * The old daemon keeps serving its sessions: the new front finds it through
     * `agent.json` and the protocol is compatible.
     */
    private suspend fun removeStale(host: Host, sftp: SshSftp?) {
        if (sftp != null && host.sftpDir != null) {
            val names = runCatching { sftp.list(host.sftpDir) }.getOrNull() ?: return
            for (name in names.filter(isStale)) runCatching { sftp.remove("${host.sftpDir}/$name") }
        } else if (host.shell == RemoteShell.POSIX) {
            val listing = runCommand("ls -1 ${AgentInstall.posixQuote(host.installDir)} 2>/dev/null")
            val stale = listing.lines().map { it.trim() }.filter { it.isNotEmpty() && isStale(it) }
            if (stale.isEmpty()) return
            val paths = stale.joinToString(" ") { AgentInstall.posixQuote("${host.installDir}/$it") }
            runCommand("rm -f $paths")
        }
    }

    /**
     * Runs [command] on an exec channel, optionally streaming [stdin] to it, and
     * returns its full stdout as text. The channel's output flow completes when
     * the command finishes.
     */
    private suspend fun runCommand(command: String, stdin: ByteArray? = null): String = coroutineScope {
        val channel: SshExecChannel = session.exec(command)
        if (stdin != null) {
            launch { channel.send(stdin) }
        }
        val out = channel.output.fold(StringBuilder()) { acc, chunk -> acc.append(chunk.decodeToString()) }
        channel.close()
        out.toString()
    }

    private companion object {
        /** `0700`: only the owner may read, write or run the agent. */
        const val MODE_0700 = 0x1C0

        private val random = SecureRandom()

        /** Keeps concurrent installs (two tabs to one host) off each other's temporary file. */
        fun randomSuffix(): String = java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * Builds an [AgentDeployer] backed by [AgentInstaller]: it installs the agent
 * for the destination and yields how to launch it, or null on
 * unsupported/failed (so the tab degrades to level 2/1). [binaryFor] supplies the
 * bytes for a detected [AgentTarget] (e.g. [AgentBinaries.load]).
 */
fun agentDeployer(
    version: String = BuildInfo.VERSION,
    isStale: (fileName: String) -> Boolean = { AgentInstall.isStaleBinary(it, version) },
    binaryFor: suspend (AgentTarget) -> ByteArray?,
): AgentDeployer = AgentDeployer { session ->
    when (val result = AgentInstaller(session, version, isStale).ensureInstalled(binaryFor)) {
        is AgentInstaller.Result.Installed -> result.launch
        else -> null
    }
}
