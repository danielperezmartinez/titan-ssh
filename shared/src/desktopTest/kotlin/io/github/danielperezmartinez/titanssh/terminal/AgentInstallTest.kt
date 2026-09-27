package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Headless coverage of the pure agent-install helpers (ADR-0008 §5, ADR-0009 §6–§7). */
class AgentInstallTest {

    @Test
    fun parses_supported_uname_combinations() {
        assertEquals(AgentTarget("linux", "amd64"), AgentInstall.parseUname("Linux", "x86_64"))
        assertEquals(AgentTarget("linux", "arm64"), AgentInstall.parseUname("Linux", "aarch64"))
        assertEquals(AgentTarget("linux", "amd64"), AgentInstall.parseUname("linux", "amd64"))
        assertEquals(AgentTarget("linux", "arm"), AgentInstall.parseUname("Linux", "armv7l"))
        assertEquals(AgentTarget("linux", "386"), AgentInstall.parseUname("Linux", "i686"))
        assertEquals(AgentTarget("linux", "riscv64"), AgentInstall.parseUname("Linux", "riscv64"))
        assertEquals(AgentTarget("linux", "ppc64le"), AgentInstall.parseUname("Linux", "ppc64le"))
        assertEquals(AgentTarget("linux", "s390x"), AgentInstall.parseUname("Linux", "s390x"))
        assertEquals(AgentTarget("darwin", "arm64"), AgentInstall.parseUname("Darwin", "arm64"))
        assertEquals(AgentTarget("darwin", "amd64"), AgentInstall.parseUname("Darwin", "x86_64"))
        assertEquals(AgentTarget("freebsd", "amd64"), AgentInstall.parseUname("FreeBSD", "amd64"))
        assertEquals(AgentTarget("freebsd", "arm64"), AgentInstall.parseUname("FreeBSD", "arm64"))
    }

    @Test
    fun rejects_unsupported_os_or_arch() {
        assertNull(AgentInstall.parseUname("Windows_NT", "x86_64"))
        assertNull(AgentInstall.parseUname("SunOS", "i86pc"))
        assertNull(AgentInstall.parseUname("Linux", "mips"))
        assertNull(AgentInstall.parseUname("Linux", "armv6l")) // GOARM=7 binaries only
        assertNull(AgentInstall.parseUname("FreeBSD", "i386")) // not a compiled target
    }

    @Test
    fun parses_the_windows_probe() {
        assertEquals(
            WindowsProbe(AgentTarget("windows", "amd64"), "C:\\Users\\u\\AppData\\Local"),
            AgentInstall.parseWindowsProbe("AMD64 %PROCESSOR_ARCHITEW6432% C:\\Users\\u\\AppData\\Local\r\n"),
        )
        assertEquals(
            WindowsProbe(AgentTarget("windows", "arm64"), "D:\\Profiles\\Ana María\\Local"),
            AgentInstall.parseWindowsProbe("ARM64 %PROCESSOR_ARCHITEW6432% D:\\Profiles\\Ana María\\Local\r\n"),
        )
        // A 32-bit process on 64-bit Windows: the real architecture is in W6432.
        assertEquals(
            AgentTarget("windows", "amd64"),
            AgentInstall.parseWindowsProbe("x86 AMD64 C:\\Users\\u\\AppData\\Local")?.target,
        )
        // 32-bit Windows: detected, but no agent for it.
        val x86 = AgentInstall.parseWindowsProbe("x86 %PROCESSOR_ARCHITEW6432% C:\\Users\\u\\AppData\\Local")
        assertNull(x86?.target)
        assertEquals("C:\\Users\\u\\AppData\\Local", x86?.localAppData)
    }

    @Test
    fun an_unexpanded_windows_probe_means_another_shell() {
        // PowerShell prints the variables as they are.
        assertNull(AgentInstall.parseWindowsProbe("%PROCESSOR_ARCHITECTURE% %PROCESSOR_ARCHITEW6432% %LOCALAPPDATA%\r\n"))
        assertNull(AgentInstall.parseWindowsProbe(""))
    }

    @Test
    fun tells_windows_sftp_paths_apart() {
        assertTrue(AgentInstall.isWindowsSftpPath("/C:/Users/u"))
        assertTrue(AgentInstall.isWindowsSftpPath("/d:"))
        assertFalse(AgentInstall.isWindowsSftpPath("/home/u"))
        assertFalse(AgentInstall.isWindowsSftpPath("/C/Users"))
        assertEquals(
            "/C:/Users/u/AppData/Local/titan-ssh",
            AgentInstall.windowsToSftpPath("C:\\Users\\u\\AppData\\Local\\titan-ssh"),
        )
    }

    @Test
    fun builds_versioned_names_and_paths() {
        val linux = AgentTarget("linux", "amd64")
        val windows = AgentTarget("windows", "arm64")
        assertEquals("agent-0.0.1-linux-amd64", AgentInstall.binaryName("0.0.1", linux))
        assertEquals("agent-0.0.1-windows-arm64.exe", AgentInstall.binaryName("0.0.1", windows))
        assertEquals("titan-agent-windows-arm64.exe", windows.fileName)
        assertEquals(
            "~/.local/share/titan-ssh/agent-0.0.1-linux-amd64",
            AgentInstall.remotePath(AgentInstall.DEFAULT_BASE_DIR, "0.0.1", linux),
        )
        assertEquals(
            "C:\\L\\titan-ssh\\agent-0.0.1-windows-arm64.exe",
            AgentInstall.remotePath("C:\\L\\titan-ssh", "0.0.1", windows, '\\'),
        )
    }

    @Test
    fun only_other_versions_binaries_are_stale() {
        assertTrue(AgentInstall.isStaleBinary("agent-0.1.0-beta.2-linux-amd64", "0.1.0-beta.3"))
        assertTrue(AgentInstall.isStaleBinary("agent-0.1.0-beta.2-windows-amd64.exe.tmp.1f", "0.1.0-beta.3"))
        assertFalse(AgentInstall.isStaleBinary("agent-0.1.0-beta.3-linux-amd64", "0.1.0-beta.3"))
        // A concurrent upload of the current version.
        assertFalse(AgentInstall.isStaleBinary("agent-0.1.0-beta.3-linux-amd64.tmp.9a", "0.1.0-beta.3"))
        // The agent's state files share the Windows directory.
        assertFalse(AgentInstall.isStaleBinary("agent.lock", "0.1.0-beta.3"))
        assertFalse(AgentInstall.isStaleBinary("agent.json", "0.1.0-beta.3"))
        // 0.1.0 must not keep 0.1.0-beta.2 because of the shared prefix.
        assertTrue(AgentInstall.isStaleBinary("agent-0.1.0-beta.2-linux-amd64", "0.1.0"))
    }

    @Test
    fun quotes_the_launch_command_for_each_shell() {
        assertEquals(
            "'/home/u/.local/share/titan-ssh/agent' --session titan-x",
            AgentInstall.command(RemoteShell.POSIX, "/home/u/.local/share/titan-ssh/agent", listOf("--session", "titan-x")),
        )
        assertEquals(
            "'/home/o'\\''brien/agent' --version",
            AgentInstall.command(RemoteShell.POSIX, "/home/o'brien/agent", listOf("--version")),
        )
        assertEquals(
            "~/'.local/share/titan-ssh/agent' --stop",
            AgentInstall.command(RemoteShell.POSIX, "~/.local/share/titan-ssh/agent", listOf("--stop")),
        )
        assertEquals(
            "\"C:\\Users\\Ana (Work)\\AppData\\Local\\titan-ssh\\agent.exe\" --session titan-x",
            AgentInstall.command(
                RemoteShell.CMD,
                "C:\\Users\\Ana (Work)\\AppData\\Local\\titan-ssh\\agent.exe",
                listOf("--session", "titan-x"),
            ),
        )
        assertEquals(
            "& 'C:\\Users\\O''Brien\\titan-ssh\\agent.exe' --session titan-x",
            AgentInstall.command(RemoteShell.POWERSHELL, "C:\\Users\\O'Brien\\titan-ssh\\agent.exe", listOf("--session", "titan-x")),
        )
        assertEquals("& 'C:\\a.exe'", AgentLaunch("C:\\a.exe", RemoteShell.POWERSHELL).command())
    }

    @Test
    fun hash_commands_quote_the_path() {
        assertEquals(
            "sha256sum '/h/a' 2>/dev/null || shasum -a 256 '/h/a' 2>/dev/null || sha256 -q '/h/a' 2>/dev/null",
            AgentInstall.hashCommand(RemoteShell.POSIX, "/h/a"),
        )
        assertEquals("certutil -hashfile \"C:\\a b\\x.exe\" SHA256", AgentInstall.hashCommand(RemoteShell.CMD, "C:\\a b\\x.exe"))
        assertEquals("certutil -hashfile 'C:\\a''b\\x.exe' SHA256", AgentInstall.hashCommand(RemoteShell.POWERSHELL, "C:\\a'b\\x.exe"))
    }

    @Test
    fun parses_sha256_from_every_tool_and_rejects_junk() {
        val hex = "a".repeat(64)
        assertEquals(hex, AgentInstall.parseSha256("$hex  /home/u/.local/share/titan-ssh/agent"))
        assertEquals(hex, AgentInstall.parseSha256(hex.uppercase() + "  file")) // case-insensitive
        assertEquals(hex, AgentInstall.parseSha256("$hex\n")) // FreeBSD `sha256 -q`
        val certutil = "SHA256 hash of C:\\x.exe:\r\n$hex\r\nCertUtil: -hashfile command completed successfully.\r\n"
        assertEquals(hex, AgentInstall.parseSha256(certutil))
        val oldCertutil = "SHA256 hash of file C:\\x.exe:\r\n${hex.chunked(2).joinToString(" ")}\r\nCertUtil: done\r\n"
        assertEquals(hex, AgentInstall.parseSha256(oldCertutil))
        assertNull(AgentInstall.parseSha256("")) // absent file: empty output
        assertNull(AgentInstall.parseSha256("sha256sum: file: No such file or directory"))
        assertNull(AgentInstall.parseSha256("CertUtil: -hashfile command FAILED: 0x80070002 (WIN32: 2 ERROR_FILE_NOT_FOUND)"))
    }
}
