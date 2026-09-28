package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.ScriptBehavior
import io.github.danielperezmartinez.titanssh.config.ScriptPhase
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * Start-script automation against a Windows destination, whose shell is
 * `cmd.exe` or PowerShell: the commands must be in that shell's syntax and
 * every line must end with `\r` (a Windows console ignores a bare `\n`, so the
 * POSIX commands used to pile up unexecuted on the prompt).
 */
class WindowsShellAutomationTest {

    /**
     * Plays a Windows console: records every line sent and answers the
     * completion sentinel of its [shell] with `token:<code>:token`, where
     * [exitFor] decides the code from the command that preceded it.
     */
    private class FakeWindowsShell(
        private val out: MutableSharedFlow<String>,
        shell: RemoteShell,
        private val exitFor: (String) -> Int = { 0 },
    ) : SshShell {
        val sent = mutableListOf<String>()
        private var last = ""
        private val sentinel = when (shell) {
            RemoteShell.CMD -> Regex("""^echo (__TITAN_[0-9a-f]+__):%errorlevel%:\1$""")
            RemoteShell.POWERSHELL -> Regex("""Write-Output \('(__TITAN_[0-9a-f]+__)' \+ ':' .* '\1'\)$""")
            RemoteShell.POSIX -> error("not a Windows shell")
        }
        override val output: Flow<ByteArray> = MutableSharedFlow()
        override suspend fun send(data: ByteArray) {
            val text = data.decodeToString()
            sent += text
            val m = sentinel.find(text.removeSuffix("\r"))
            if (m != null) {
                val token = m.groupValues[1]
                out.emit("$token:${exitFor(last)}:$token\r\n")
            } else {
                last = text.trim()
            }
        }
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() {}

        /** The lines sent, sentinels excluded, without their line ending. */
        fun commands(): List<String> =
            sent.filterNot { sentinel.containsMatchIn(it.removeSuffix("\r")) }.map { it.removeSuffix("\r") }
    }

    private class EmptySecretStore : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) {}
        override suspend fun get(ref: SecretRef): ByteArray? = null
        override suspend fun remove(ref: SecretRef) {}
        override suspend fun contains(ref: SecretRef): Boolean = false
    }

    private fun newOut() = MutableSharedFlow<String>(replay = 8, extraBufferCapacity = 64)

    private fun io(fake: SshShell, out: MutableSharedFlow<String>, shell: RemoteShell, onMux: (TerminalMultiplexer.Kind) -> Unit = {}) =
        ShellIo(fake, out, onMux) { shell }

    private fun resolved(level: ResilienceLevel, scripts: List<SessionScript> = emptyList()) = ResolvedConnection(
        session = Session(id = "s", name = "n", hostId = "h", initialDirectory = "D:\\work\\", resilienceLevel = level, scripts = scripts),
        host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r")),
        endpoint = SshEndpoint("x", 22, "u"),
        auth = HostAuth.Password("r"),
        appearance = TerminalAppearance(),
        proxyJump = null,
    )

    @Test
    fun probe_tells_the_shells_apart() {
        assertEquals(RemoteShell.CMD, ShellSyntax.parseProbe("Windows_NT \$PSHOME\r\n"))
        assertEquals(
            RemoteShell.POWERSHELL,
            ShellSyntax.parseProbe("%OS%\r\nC:\\Windows\\System32\\WindowsPowerShell\\v1.0\r\n"),
        )
        assertEquals(RemoteShell.POSIX, ShellSyntax.parseProbe("%OS%\n"))
        assertEquals(RemoteShell.POSIX, ShellSyntax.parseProbe(""))
    }

    @Test
    fun cmd_gets_cmd_syntax_and_carriage_returns() = runTest {
        val out = newOut()
        val fake = FakeWindowsShell(out, RemoteShell.CMD)
        val script = SessionScript(
            id = "a", label = "a", phase = ScriptPhase.ON_SHELL_START,
            body = "git status\ndir", envVars = mapOf("MODE" to "dev"),
        )

        val outcomes = ScriptRunner(io(fake, out, RemoteShell.CMD), { null }, shell = RemoteShell.CMD)
            .run(listOf(script), initialDirectory = "D:\\work\\")

        assertEquals(listOf("cd /d \"D:\\work\\\"", "set \"MODE=dev\"\rgit status\rdir"), fake.commands())
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED), outcomes.map { it.status })
        assertFalse(fake.sent.any { '\n' in it }, "a Windows console only takes \\r as Enter: ${fake.sent}")
        assertTrue(fake.sent.all { it.endsWith("\r") })
    }

    @Test
    fun cmd_reports_a_failed_cd_and_skips_the_scripts_after_it() = runTest {
        val out = newOut()
        val fake = FakeWindowsShell(out, RemoteShell.CMD, exitFor = { if (it.startsWith("cd ")) 1 else 0 })
        val script = SessionScript(id = "a", label = "a", phase = ScriptPhase.ON_SHELL_START, body = "dir")

        val outcomes = ScriptRunner(io(fake, out, RemoteShell.CMD), { null }, shell = RemoteShell.CMD)
            .run(listOf(script), initialDirectory = "Z:\\missing")

        assertEquals(listOf(RunStatus.FAILED, RunStatus.ABORTED), outcomes.map { it.status })
        assertEquals(1, outcomes.first().exitCode)
    }

    @Test
    fun a_lone_cd_prints_no_sentinel() = runTest {
        val out = newOut()
        val fake = FakeWindowsShell(out, RemoteShell.CMD)

        val outcomes = ScriptRunner(io(fake, out, RemoteShell.CMD), { null }, shell = RemoteShell.CMD)
            .run(emptyList(), initialDirectory = "D:\\work\\")

        assertEquals(listOf("cd /d \"D:\\work\\\"\r"), fake.sent)
        assertEquals(RunStatus.SENT, outcomes.single().status)
    }

    @Test
    fun powershell_gets_powershell_syntax() = runTest {
        val out = newOut()
        val fake = FakeWindowsShell(out, RemoteShell.POWERSHELL)
        val script = SessionScript(
            id = "a", label = "a", phase = ScriptPhase.ON_SHELL_START, body = "Get-Location",
            envVars = mapOf("NAME" to "o'brien"), behavior = ScriptBehavior(waitForCompletion = false),
        )

        val outcomes = ScriptRunner(io(fake, out, RemoteShell.POWERSHELL), { null }, shell = RemoteShell.POWERSHELL)
            .run(listOf(script), initialDirectory = "C:\\Users\\O'Brien")

        assertEquals(
            listOf("Set-Location -LiteralPath 'C:\\Users\\O''Brien'", "\$env:NAME = 'o''brien'\rGet-Location"),
            fake.commands(),
        )
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.SENT), outcomes.map { it.status })
    }

    @Test
    fun a_windows_session_skips_the_multiplexer_and_still_runs_its_start_chain() = runTest {
        val out = newOut()
        val fake = FakeWindowsShell(out, RemoteShell.CMD)
        val reported = mutableListOf<TerminalMultiplexer.Kind>()
        out.tryEmit("C:\\Users\\u>")
        val scripts = listOf(SessionScript(id = "a", label = "a", phase = ScriptPhase.ON_SHELL_START, body = "echo hi"))

        StartScriptAutomation(EmptySecretStore(), multiplexerSettleMillis = 0)
            .onShellReady(io(fake, out, RemoteShell.CMD) { reported += it }, resolved(ResilienceLevel.AUTO_MULTIPLEXER, scripts))

        assertEquals(listOf(TerminalMultiplexer.Kind.NONE), reported)
        assertEquals(listOf("cd /d \"D:\\work\\\"", "echo hi"), fake.commands())
        assertFalse(fake.sent.any { "printf" in it || "tmux" in it }, "no POSIX probe on Windows: ${fake.sent}")
    }
}
