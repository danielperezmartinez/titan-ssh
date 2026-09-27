package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshConnectionState
import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The level-3 diagnostics ([[Diagnóstico cuando el nivel 3 no está
 * disponible]]): reading the agent's error contract, telling "level 3 is
 * unavailable" from a drop in [AgentTransport], the systemd probe and the
 * user-facing text.
 */
class AgentDiagnosticsTest {

    @Test
    fun parses_the_contract_line_from_stderr() {
        val stderr = "some noise\nTITAN_AGENT_ERROR E_STATE_DIR state dir /home/u/.local/state/titan-ssh is group-writable\n"
        assertEquals(
            AgentIssue("E_STATE_DIR", "state dir /home/u/.local/state/titan-ssh is group-writable"),
            AgentDiagnostics.parseContract(stderr),
        )
        assertNull(AgentDiagnostics.parseContract("titan-agent: something else\n"))
        // A reason without a code is still reported, as a generic exit.
        assertEquals(AgentIssue(AgentDiagnostics.E_AGENT_EXIT, "weird"), AgentDiagnostics.parseReason("weird"))
    }

    @Test
    fun classifies_a_front_that_never_served() {
        assertEquals(
            "E_JOB_NO_BREAKAWAY",
            AgentDiagnostics.classifyFrontExit("TITAN_AGENT_ERROR E_JOB_NO_BREAKAWAY job kills\r\n", 1)?.code,
        )
        // sh: found but not runnable (noexec) and not found.
        assertEquals(
            AgentIssue(AgentDiagnostics.E_NOEXEC, "sh: 1: /home/u/.local/share/titan-ssh/agent: Permission denied"),
            AgentDiagnostics.classifyFrontExit("sh: 1: /home/u/.local/share/titan-ssh/agent: Permission denied\n", 126),
        )
        assertEquals(AgentDiagnostics.E_NOEXEC, AgentDiagnostics.classifyFrontExit("", 127)?.code)
        // Windows AppLocker: cmd says why and exits 1.
        assertEquals(
            AgentIssue(AgentDiagnostics.E_AGENT_EXIT, "This program is blocked by group policy."),
            AgentDiagnostics.classifyFrontExit("This program is blocked by group policy.\r\n", 1),
        )
        // No exit status: the connection dropped, which is not a diagnosis.
        assertNull(AgentDiagnostics.classifyFrontExit("", null))
    }

    @Test
    fun reads_the_systemd_probe() {
        fun warn(out: String) = AgentDiagnostics.parseSystemdProbe(out)
        assertEquals(AgentDiagnostics.E_SYSTEMD_KILL, warn("kill=b true\nLinger=no\n")?.code)
        assertEquals(AgentDiagnostics.E_SYSTEMD_KILL, warn("kill=yes\nLinger=no\n")?.code) // logind.conf fallback
        assertNull(warn("kill=b true\nLinger=yes\n"))
        assertNull(warn("kill=b false\nLinger=no\n"))
        assertNull(warn("kill=\n")) // no systemd
        assertNull(warn("kill=yes\n")) // no loginctl answer: cannot tell
        assertTrue(AgentIssue(AgentDiagnostics.E_SYSTEMD_KILL).isWarning)
        assertFalse(AgentIssue(AgentDiagnostics.E_NOEXEC).isWarning)
    }

    @Test
    fun the_probe_is_one_posix_command() {
        assertTrue(AgentDiagnostics.SYSTEMD_PROBE.startsWith("sh -c '"))
        assertTrue(AgentDiagnostics.SYSTEMD_PROBE.contains("KillUserProcesses"))
        assertTrue(AgentDiagnostics.SYSTEMD_PROBE.contains("-p Linger"))
    }

    @Test
    fun describes_every_code_in_spanish_with_the_code() {
        val codes = listOf(
            "E_STATE_DIR", "E_LOCK", "E_DAEMON_START", "E_AUTH", "E_JOB_NO_BREAKAWAY", "E_NO_CONPTY", "E_PTY",
            "E_UNSUPPORTED_TARGET", "E_NO_BINARY", "E_UPLOAD", "E_CHECKSUM", "E_NOEXEC", "E_AGENT_EXIT",
        )
        for (code in codes) {
            val text = AgentDiagnostics.describe(AgentIssue(code, "why"))
            assertTrue(text.startsWith("Nivel 3 no disponible: "), text)
            assertTrue(text.endsWith("($code)"), text)
            assertFalse(text.contains("desconocido"), "$code has no message of its own: $text")
        }
        assertTrue(AgentDiagnostics.describe(AgentIssue("E_PTY", "no shell")).contains("Detalle: no shell"))
        assertFalse(AgentDiagnostics.describe(AgentIssue("E_STATE_DIR", "/home/u")).contains("/home/u"))
        assertTrue(AgentDiagnostics.describe(AgentIssue("E_NEW", "later")).contains("Detalle: later"))
        assertTrue(AgentDiagnostics.describe(AgentIssue(AgentDiagnostics.E_SYSTEMD_KILL)).startsWith("Aviso del nivel 3"))
    }

    /** An agent channel that plays back [frames] and [stderr], then ends with [exitStatus]. */
    private class ScriptedExec(
        frames: List<AgentFrame>,
        stderr: String,
        private val exitStatus: Int?,
    ) : SshExecChannel {
        override val output: Flow<ByteArray> =
            if (frames.isEmpty()) emptyFlow() else flowOf(frames.fold(ByteArray(0)) { acc, f -> acc + AgentProtocol.encode(f) })
        override val errors: Flow<ByteArray> = if (stderr.isEmpty()) emptyFlow() else flowOf(stderr.encodeToByteArray())
        override suspend fun send(data: ByteArray) {}
        override suspend fun close(): Int? = exitStatus
    }

    private class OneExecSession(private val ch: SshExecChannel) : SshSession {
        override val state = MutableStateFlow(SshConnectionState.CONNECTED)
        override suspend fun openShell(columns: Int, rows: Int): SshShell = error("not used")
        override suspend fun exec(command: String): SshExecChannel = ch
        override suspend fun close() {}
    }

    private fun runTransport(ch: SshExecChannel): AgentTransport = runBlocking {
        AgentTransport(
            session = OneExecSession(ch),
            agentSessionId = "s",
            agent = AgentLaunch("/home/u/agent", RemoteShell.POSIX),
            onOutput = {},
            initialColumns = 80,
            initialRows = 24,
        ).also { it.run() }
    }

    @Test
    fun a_refusing_bye_makes_level_3_unavailable() {
        val t = runTransport(ScriptedExec(listOf(AgentFrame.Bye("E_NO_CONPTY ConPTY needs Windows 10 1809")), "", 0))
        assertEquals(AgentIssue("E_NO_CONPTY", "ConPTY needs Windows 10 1809"), t.unavailable)

        // An agent that predates the reason refused only when its PTY failed.
        assertEquals(AgentDiagnostics.E_PTY, runTransport(ScriptedExec(listOf(AgentFrame.Bye()), "", 0)).unavailable?.code)
    }

    @Test
    fun a_front_that_fails_reports_its_contract_line() {
        val t = runTransport(ScriptedExec(emptyList(), "TITAN_AGENT_ERROR E_LOCK flock: not supported\n", 1))
        assertEquals(AgentIssue("E_LOCK", "flock: not supported"), t.unavailable)
    }

    @Test
    fun a_drop_or_a_served_session_is_not_a_diagnosis() {
        // The connection dropped before HELLO_OK: no exit status.
        assertNull(runTransport(ScriptedExec(emptyList(), "", null)).unavailable)
        // The session was served, then the shell exited.
        val served = listOf(AgentFrame.HelloOk(0, 0, created = true), AgentFrame.Data(0, "bye\r\n".encodeToByteArray()), AgentFrame.Bye())
        assertNull(runTransport(ScriptedExec(served, "", 0)).unavailable)
    }
}
