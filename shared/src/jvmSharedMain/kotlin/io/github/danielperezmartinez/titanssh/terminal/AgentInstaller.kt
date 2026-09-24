package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.BuildInfo
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import java.security.MessageDigest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch

/**
 * Installs the level-3 agent binary on a destination over the SSH `exec` channel
 * (ADR-0008 §5), reusing [SshSession.exec] — no SFTP surface needed.
 *
 * Flow: detect the host's OS/arch with `uname`, pick the matching binary, and if
 * the versioned remote file's SHA-256 does not already match, upload it (piping
 * the exact byte count through `head -c`, so no stdin half-close is needed),
 * `chmod 0700` and re-verify the checksum. Everything lives in user space
 * (`~/.local/share/titan-ssh`), never root. On any failure the caller degrades to
 * resilience level 2/1.
 */
class AgentInstaller(
    private val session: SshSession,
    private val version: String = BuildInfo.VERSION,
    private val baseDir: String = AgentInstall.DEFAULT_BASE_DIR,
) {

    /** Outcome of [ensureInstalled]. */
    sealed interface Result {
        /** The binary is in place at [path]; [uploaded] is false when already present. */
        data class Installed(val path: String, val target: AgentTarget, val uploaded: Boolean) : Result
        /** The destination's OS/arch has no agent binary (or `uname` failed). */
        data class Unsupported(val reason: String) : Result
        /** Upload happened but verification failed. */
        data class Failed(val reason: String) : Result
    }

    /** Runs `uname -s` / `uname -m` on the destination and maps it to a target. */
    suspend fun detectTarget(): AgentTarget? {
        val out = runCommand("uname -s; uname -m")
        val lines = out.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        return AgentInstall.parseUname(lines[0], lines[1])
    }

    /**
     * Ensures the agent binary for the destination is installed and returns its
     * remote path. [binaryFor] supplies the bytes for a target (e.g. from bundled
     * resources), or null if none is shipped for it.
     */
    suspend fun ensureInstalled(binaryFor: suspend (AgentTarget) -> ByteArray?): Result {
        val target = detectTarget() ?: return Result.Unsupported("could not detect the destination OS/arch")
        val bytes = binaryFor(target) ?: return Result.Unsupported("no agent binary bundled for ${target.slug}")
        val expected = sha256Hex(bytes)
        val path = AgentInstall.remotePath(baseDir, version, target)

        // Idempotent: skip the upload if the versioned file already matches.
        val existing = AgentInstall.parseSha256(runCommand("sha256sum $path 2>/dev/null"))
        if (existing == expected) return Result.Installed(path, target, uploaded = false)

        val tmp = "$path.tmp.$version"
        val script = "mkdir -p $baseDir && head -c ${bytes.size} > $tmp && " +
            "chmod 700 $tmp && mv $tmp $path && sha256sum $path"
        val out = runCommand(script, stdin = bytes)
        val got = AgentInstall.parseSha256(out)
        return if (got == expected) {
            Result.Installed(path, target, uploaded = true)
        } else {
            Result.Failed("checksum mismatch after upload (expected $expected, got ${got ?: "none"})")
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
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * Builds an [AgentDeployer] backed by [AgentInstaller]: it installs the bundled
 * binary for the destination and yields its remote path, or null on
 * unsupported/failed (so the tab degrades to level 2/1). [binaryFor] supplies the
 * bytes for a detected [AgentTarget] (e.g. from app resources).
 */
fun agentDeployer(
    version: String = BuildInfo.VERSION,
    binaryFor: suspend (AgentTarget) -> ByteArray?,
): AgentDeployer = AgentDeployer { session ->
    when (val result = AgentInstaller(session, version).ensureInstalled(binaryFor)) {
        is AgentInstaller.Result.Installed -> result.path
        else -> null
    }
}
