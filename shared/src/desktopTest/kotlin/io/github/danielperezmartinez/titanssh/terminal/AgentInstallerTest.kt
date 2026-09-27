package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Drives [AgentInstaller] against a fake host (exec answers + in-memory SFTP) to
 * cover the per-OS flows of ADR-0009 §7 headless: Windows under cmd and
 * PowerShell, Unix with and without SFTP, idempotency, cleanup and failures.
 */
class AgentInstallerTest {

    private val binary = ByteArray(4096) { (it * 31).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** In-memory SFTP. Files listed in [locked] refuse deletion, like a running `.exe`. */
    private class FakeSftp(val home: String) : SshSftp {
        val files = linkedMapOf<String, ByteArray>()
        val modes = mutableMapOf<String, Int>()
        val locked = mutableSetOf<String>()
        var writes = 0
        var corruptWrites = false

        override suspend fun canonicalize(path: String) = if (path == ".") home else path
        override suspend fun size(path: String) = files[path]?.size?.toLong()
        override suspend fun list(directory: String) =
            files.keys.filter { it.substringBeforeLast('/') == directory }.map { it.substringAfterLast('/') }
        override suspend fun mkdirs(directory: String) {}
        override suspend fun write(path: String, bytes: ByteArray) {
            writes++
            files[path] = if (corruptWrites) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes.copyOf()
        }
        override suspend fun read(path: String) = files[path] ?: throw IOException("no such file: $path")
        override suspend fun chmod(path: String, mode: Int) { modes[path] = mode }
        override suspend fun rename(from: String, to: String) {
            if (to in files) throw IOException("target exists")
            files[to] = files.remove(from) ?: throw IOException("no such file: $from")
            modes.remove(from)?.let { modes[to] = it }
        }
        override suspend fun remove(path: String) {
            if (path in locked) throw IOException("in use")
            files.remove(path) ?: throw IOException("no such file: $path")
        }
        override suspend fun close() {}
    }

    /** A host whose exec answers come from [answer]; records commands and stdin. */
    private class FakeHost(val sftp: FakeSftp?, val answer: FakeHost.(String) -> String) : SshSession {
        val commands = mutableListOf<String>()
        val stdin = mutableListOf<ByteArray>()

        override val state: StateFlow<SshConnectionState> = MutableStateFlow(SshConnectionState.CONNECTED)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("no shell")
        override suspend fun openSftp(): SshSftp? = sftp
        override suspend fun exec(command: String): SshExecChannel {
            commands += command
            val out = answer(command)
            return object : SshExecChannel {
                override val output: Flow<ByteArray> = flowOf(out.encodeToByteArray())
                override val errors: Flow<ByteArray> = emptyFlow()
                override suspend fun send(data: ByteArray) { stdin += data }
                override suspend fun close(): Int = 0
            }
        }
        override suspend fun close() {}
    }

    private val windowsProbe = "AMD64 %PROCESSOR_ARCHITEW6432% C:\\Users\\u\\AppData\\Local\r\n"
    private val winDir = "/C:/Users/u/AppData/Local/titan-ssh"

    /** A Windows host without certutil output: verification falls back to SFTP. */
    private fun windowsHost(sftp: FakeSftp, powershell: Boolean = false) = FakeHost(sftp) { cmd ->
        when {
            cmd == AgentInstall.WINDOWS_PROBE ->
                if (powershell) "%PROCESSOR_ARCHITECTURE% %PROCESSOR_ARCHITEW6432% %LOCALAPPDATA%\r\n" else windowsProbe
            cmd == "cmd /c ${AgentInstall.WINDOWS_PROBE}" -> windowsProbe
            else -> "CertUtil: -hashfile command FAILED\r\n"
        }
    }

    /** A Linux host whose `sha256sum` hashes the fake SFTP files. */
    private fun linuxHost(sftp: FakeSftp?) = FakeHost(sftp) { cmd ->
        when {
            cmd == "uname -s; uname -m" -> "Linux\nx86_64\n"
            cmd.startsWith("sha256sum ") -> {
                val path = cmd.substringAfter("sha256sum '").substringBefore("'")
                sftp?.files?.get(path)?.let { "${sha256(it)}  $path\n" } ?: ""
            }
            else -> ""
        }
    }

    @Test
    fun installs_on_windows_under_cmd_and_cleans_up_other_versions() = runBlocking<Unit> {
        val sftp = FakeSftp("/C:/Users/u")
        sftp.files["$winDir/agent-0.9.0-windows-amd64.exe"] = byteArrayOf(1)
        sftp.files["$winDir/agent-0.8.0-windows-amd64.exe"] = byteArrayOf(2)
        sftp.locked += "$winDir/agent-0.8.0-windows-amd64.exe" // its daemon still runs
        sftp.files["$winDir/agent.json"] = byteArrayOf(3)
        val host = windowsHost(sftp)
        var asked: AgentTarget? = null

        val result = AgentInstaller(host, version = "1.0.0").ensureInstalled { asked = it; binary }

        val installed = assertIs<AgentInstaller.Result.Installed>(result)
        assertEquals(AgentTarget("windows", "amd64"), asked)
        assertTrue(installed.uploaded)
        assertEquals(
            AgentLaunch("C:\\Users\\u\\AppData\\Local\\titan-ssh\\agent-1.0.0-windows-amd64.exe", RemoteShell.CMD),
            installed.launch,
        )
        assertTrue(binary.contentEquals(sftp.files["$winDir/agent-1.0.0-windows-amd64.exe"]))
        assertTrue(sftp.modes.isEmpty(), "no chmod on Windows")
        assertFalse("$winDir/agent-0.9.0-windows-amd64.exe" in sftp.files, "old version removed")
        assertTrue("$winDir/agent-0.8.0-windows-amd64.exe" in sftp.files, "a running one is left for later")
        assertTrue("$winDir/agent.json" in sftp.files, "the agent's state is never touched")
        assertTrue(sftp.files.keys.none { ".tmp." in it }, "no temporary file left behind")
    }

    @Test
    fun detects_powershell_and_quotes_for_it() = runBlocking<Unit> {
        val sftp = FakeSftp("/C:/Users/u")
        val host = windowsHost(sftp, powershell = true)

        val installed = assertIs<AgentInstaller.Result.Installed>(AgentInstaller(host, version = "1.0.0").ensureInstalled { binary })

        assertEquals(RemoteShell.POWERSHELL, installed.launch.shell)
        assertEquals(
            "& 'C:\\Users\\u\\AppData\\Local\\titan-ssh\\agent-1.0.0-windows-amd64.exe' --session titan-s",
            installed.launch.command("--session", "titan-s"),
        )
        assertTrue(host.commands.any { it.startsWith("certutil -hashfile '") })
    }

    @Test
    fun a_matching_windows_install_is_not_uploaded_again() = runBlocking<Unit> {
        val sftp = FakeSftp("/C:/Users/u")
        val host = windowsHost(sftp)
        AgentInstaller(host, version = "1.0.0").ensureInstalled { binary }
        val writes = sftp.writes

        val again = assertIs<AgentInstaller.Result.Installed>(AgentInstaller(host, version = "1.0.0").ensureInstalled { binary })

        assertFalse(again.uploaded)
        assertEquals(writes, sftp.writes)
    }

    @Test
    fun installs_on_unix_over_sftp_with_owner_only_permissions() = runBlocking<Unit> {
        val sftp = FakeSftp("/home/u")
        sftp.files["/home/u/.local/share/titan-ssh/agent-0.9.0-linux-amd64"] = byteArrayOf(1)
        val host = linuxHost(sftp)

        val installed = assertIs<AgentInstaller.Result.Installed>(AgentInstaller(host, version = "1.0.0").ensureInstalled { binary })

        val path = "/home/u/.local/share/titan-ssh/agent-1.0.0-linux-amd64"
        assertEquals(AgentLaunch(path, RemoteShell.POSIX), installed.launch)
        assertEquals(0x1C0, sftp.modes[path])
        assertTrue(binary.contentEquals(sftp.files[path]))
        assertEquals(setOf(path), sftp.files.keys, "the old version is removed")

        // Reconnecting: sha256sum answers, nothing is read back or uploaded.
        val writes = sftp.writes
        val again = assertIs<AgentInstaller.Result.Installed>(AgentInstaller(host, version = "1.0.0").ensureInstalled { binary })
        assertFalse(again.uploaded)
        assertEquals(writes, sftp.writes)
    }

    @Test
    fun replaces_a_corrupted_install() = runBlocking<Unit> {
        val sftp = FakeSftp("/home/u")
        val path = "/home/u/.local/share/titan-ssh/agent-1.0.0-linux-amd64"
        sftp.files[path] = binary.copyOf().also { it[10] = 0 }
        val installed = assertIs<AgentInstaller.Result.Installed>(
            AgentInstaller(linuxHost(sftp), version = "1.0.0").ensureInstalled { binary },
        )
        assertTrue(installed.uploaded)
        assertTrue(binary.contentEquals(sftp.files[path]))
    }

    @Test
    fun falls_back_to_exec_on_unix_without_sftp() = runBlocking<Unit> {
        var uploadedBytes: ByteArray? = null
        val host = FakeHost(sftp = null) { cmd ->
            when {
                cmd == "uname -s; uname -m" -> "Darwin\narm64\n"
                cmd.startsWith("sha256sum ") -> uploadedBytes?.let { "${sha256(it)}  x\n" } ?: ""
                cmd.startsWith("mkdir -p ") -> { uploadedBytes = binary; "" }
                else -> ""
            }
        }

        val installed = assertIs<AgentInstaller.Result.Installed>(AgentInstaller(host, version = "1.0.0").ensureInstalled { binary })

        assertEquals("~/.local/share/titan-ssh/agent-1.0.0-darwin-arm64", installed.path)
        assertEquals("~/'.local/share/titan-ssh/agent-1.0.0-darwin-arm64' --version", installed.launch.command("--version"))
        val upload = host.commands.single { it.startsWith("mkdir -p ") }
        assertTrue(upload.contains("head -c ${binary.size} > "), upload)
        assertTrue(host.stdin.single().contentEquals(binary))
    }

    @Test
    fun reports_unsupported_targets_and_missing_binaries() = runBlocking<Unit> {
        val sun = FakeHost(sftp = FakeSftp("/export/home/u")) { if (it.startsWith("uname")) "SunOS\ni86pc\n" else "" }
        assertIs<AgentInstaller.Result.Unsupported>(AgentInstaller(sun).ensureInstalled { binary })

        val x86 = FakeHost(FakeSftp("/C:/Users/u")) { "x86 %PROCESSOR_ARCHITEW6432% C:\\Users\\u\\AppData\\Local\r\n" }
        assertIs<AgentInstaller.Result.Unsupported>(AgentInstaller(x86).ensureInstalled { binary })

        val noBinary = AgentInstaller(linuxHost(FakeSftp("/home/u"))).ensureInstalled { null }
        assertIs<AgentInstaller.Result.Unsupported>(noBinary)
    }

    @Test
    fun a_bad_upload_fails_and_is_never_launched() = runBlocking<Unit> {
        val sftp = FakeSftp("/C:/Users/u").apply { corruptWrites = true }
        val result = AgentInstaller(windowsHost(sftp), version = "1.0.0").ensureInstalled { binary }
        assertIs<AgentInstaller.Result.Failed>(result)
        assertNull(agentDeployer(version = "1.0.0") { binary }.ensureInstalled(windowsHost(sftp)))
    }
}
