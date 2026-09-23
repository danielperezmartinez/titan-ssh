package io.github.danielperezmartinez.titanssh.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Headless coverage of the pure agent-install helpers (ADR-0008 §5). */
class AgentInstallTest {

    @Test
    fun parses_supported_uname_combinations() {
        assertEquals(AgentTarget("linux", "amd64"), AgentInstall.parseUname("Linux", "x86_64"))
        assertEquals(AgentTarget("linux", "arm64"), AgentInstall.parseUname("Linux", "aarch64"))
        assertEquals(AgentTarget("linux", "amd64"), AgentInstall.parseUname("linux", "amd64"))
        assertEquals(AgentTarget("darwin", "arm64"), AgentInstall.parseUname("Darwin", "arm64"))
    }

    @Test
    fun rejects_unsupported_os_or_arch() {
        assertNull(AgentInstall.parseUname("Windows_NT", "x86_64"))
        assertNull(AgentInstall.parseUname("Linux", "riscv64"))
    }

    @Test
    fun builds_versioned_remote_path() {
        val target = AgentTarget("linux", "amd64")
        assertEquals(
            "~/.local/share/titan-ssh/agent-0.0.1-linux-amd64",
            AgentInstall.remotePath(AgentInstall.DEFAULT_BASE_DIR, "0.0.1", target),
        )
    }

    @Test
    fun parses_sha256sum_output_and_rejects_junk() {
        val hex = "a".repeat(64)
        assertEquals(hex, AgentInstall.parseSha256("$hex  /home/u/.local/share/titan-ssh/agent"))
        assertEquals(hex, AgentInstall.parseSha256(hex.uppercase() + "  file")) // case-insensitive
        assertNull(AgentInstall.parseSha256("")) // absent file: empty output
        assertNull(AgentInstall.parseSha256("sha256sum: file: No such file or directory"))
    }
}
