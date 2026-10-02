package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.LibraryScript
import io.github.danielperezmartinez.titanssh.config.ReconnectBehavior
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.ScriptBehavior
import io.github.danielperezmartinez.titanssh.config.ScriptFailurePolicy
import io.github.danielperezmartinez.titanssh.config.ScriptPhase
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ScriptRunnerTest {

    /**
     * A shell that records everything sent and, crucially, plays the role of the
     * remote for completion sentinels: when it sees the `printf ...:$?:...` line,
     * it echoes back `token:<code>:token` so an awaited command can complete.
     * [exitFor] decides the exit code from the command that preceded the sentinel.
     */
    private class FakeShell(
        private val out: MutableSharedFlow<String>,
        /** Whether it confirms a [ShellSyntax.hiddenInput] line, as a shell that turned echo off would. */
        private val hidesInput: Boolean = true,
        private val exitFor: (String) -> Int = { 0 },
    ) : SshShell {
        val sent = mutableListOf<String>()

        /** Blocks read through [ShellSyntax.hiddenInput], decoded. */
        val hiddenBlocks = mutableListOf<String>()
        private var last = ""
        private var hiddenToken: String? = null
        override val output: Flow<ByteArray> = MutableSharedFlow()
        override suspend fun send(data: ByteArray) {
            val text = data.decodeToString()
            sent += text
            hiddenToken?.let { token ->
                val lines = text.split('\n').dropLast(1)
                check(lines.last() == token) { "hidden block not ended by its token: $text" }
                last = decodePrintfB(lines.dropLast(1).joinToString(""))
                hiddenBlocks += last
                hiddenToken = null
                return
            }
            val sentinel = SENTINEL.find(text)
            val hidden = HIDDEN.find(text)
            when {
                sentinel != null -> {
                    val token = sentinel.groupValues[1]
                    out.emit("$token:${exitFor(last)}:$token\n")
                }
                hidden != null -> if (hidesInput) {
                    hiddenToken = hidden.groupValues[1]
                    out.emit("${hidden.groupValues[1]}:hidden\n")
                }
                else -> last = text.trim()
            }
        }
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() {}

        /** The whole conversation, for substring assertions. */
        fun log(): String = sent.joinToString("")

        companion object {
            val SENTINEL = Regex("""'(__TITAN_[0-9a-f]+__)' "\$\?" '\1'""")
            val HIDDEN = Regex("""^stty -echo && \{ printf '%s:%s\\n' '(__TITAN_[0-9a-f]+__)' 'hidden'""")

            /** What `printf '%b'` prints for the escapes [ShellSyntax.hiddenInputBlock] uses. */
            fun decodePrintfB(s: String): String = buildString {
                var i = 0
                while (i < s.length) {
                    if (s[i] != '\\') { append(s[i++]); continue }
                    when (s[i + 1]) {
                        '\\' -> { append('\\'); i += 2 }
                        'n' -> { append('\n'); i += 2 }
                        '0' -> { append(s.substring(i + 2, i + 5).toInt(8).toChar()); i += 5 }
                        else -> error("unexpected escape in ${s.substring(i)}")
                    }
                }
            }
        }
    }

    private class FakeSecretStore(private val map: Map<String, ByteArray>) : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) {}
        override suspend fun get(ref: SecretRef): ByteArray? = map[ref.value]
        override suspend fun remove(ref: SecretRef) {}
        override suspend fun contains(ref: SecretRef): Boolean = map.containsKey(ref.value)
    }

    private fun newOut() = MutableSharedFlow<String>(replay = 8, extraBufferCapacity = 64)

    private fun script(
        id: String,
        body: String,
        behavior: ScriptBehavior = ScriptBehavior(),
        phase: ScriptPhase = ScriptPhase.ON_SHELL_START,
        enabled: Boolean = true,
        envVars: Map<String, String> = emptyMap(),
        secretRefs: List<String> = emptyList(),
    ) = SessionScript(
        id = id, label = id, enabled = enabled, phase = phase, body = body,
        behavior = behavior, envVars = envVars, secretRefs = secretRefs,
    )

    @Test
    fun cd_runs_first_then_scripts_in_order() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        val outcomes = runner.run(
            scripts = listOf(script("a", "alpha"), script("b", "beta")),
            initialDirectory = "/srv/app",
        )

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/srv/app'", "alpha", "beta"), commands)
        // Only the units something waits for print a sentinel: not the last one.
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.SENT), outcomes.map { it.status })
        assertEquals("beta\n", fake.sent.last())
    }

    @Test
    fun no_scripts_and_no_cd_sends_nothing() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val outcomes = ScriptRunner(ShellIo(fake, out), { null }).run(emptyList(), null)
        assertTrue(fake.sent.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun substitutes_secret_placeholders_only() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { ref -> if (ref == "token") "s3cret" else null })

        runner.run(
            scripts = listOf(script("a", "deploy \${token} to \${HOME}", secretRefs = listOf("token"))),
            initialDirectory = null,
        )

        assertEquals(listOf("deploy s3cret to \${HOME}"), fake.hiddenBlocks, "secret substituted, shell var left alone")
        assertFalse(fake.log().contains("\${token}"))
    }

    @Test
    fun script_with_secrets_is_read_by_the_shell_without_echo() = runTest {
        val out = newOut()
        val fake = FakeShell(out, exitFor = { if (it.contains("s3cret")) 3 else 0 })
        val runner = ScriptRunner(ShellIo(fake, out), { ref -> if (ref == "token") "s3cret" else null })

        val outcomes = runner.run(
            scripts = listOf(
                script(
                    "a", "login\n  --key \${token}",
                    behavior = ScriptBehavior(waitForCompletion = true),
                    envVars = mapOf("API" to "\${token}"), secretRefs = listOf("token"),
                ),
                script("b", "next"),
            ),
            initialDirectory = null,
        )

        // Typed at the prompt: the line that turns echo off, then the block once
        // the shell confirmed, then the sentinel. Only the block holds the secret.
        assertTrue(fake.sent[0].startsWith("stty -echo && "))
        assertFalse(fake.sent[0].contains("s3cret"))
        assertEquals(listOf("export API='s3cret'\nlogin\n  --key s3cret"), fake.hiddenBlocks)
        assertTrue(fake.sent[2].startsWith("printf "))
        assertEquals(listOf(RunStatus.FAILED, RunStatus.SENT), outcomes.map { it.status })
        assertEquals(3, outcomes.first().exitCode)
        assertEquals("next\n", fake.sent.last())
    }

    @Test
    fun script_with_secrets_waits_for_the_shell_before_sending_them() = runTest {
        val out = newOut()
        val fake = FakeShell(out, hidesInput = false)
        val runner = ScriptRunner(ShellIo(fake, out), { "s3cret" })

        val outcomes = runner.run(
            scripts = listOf(script("a", "use \${x}", behavior = ScriptBehavior(timeoutSeconds = 2), secretRefs = listOf("x"))),
            initialDirectory = null,
        )

        assertEquals(RunStatus.TIMED_OUT, outcomes.single().status)
        assertEquals(1, fake.sent.size)
        assertFalse(fake.log().contains("s3cret"))
    }

    @Test
    fun hidden_block_lines_carry_only_printable_text() {
        val token = "__TITAN_0123456789abcdef__"
        val block = "a\\b\tc\u001b[0m\u0003\u007f\n" + "x".repeat(700) + "😀".repeat(300) + "ñ"
        val lines = ShellSyntax.hiddenInputBlock(RemoteShell.POSIX, token, block).split('\n').dropLast(1)

        assertEquals(token, lines.last())
        val body = lines.dropLast(1)
        assertTrue(body.all { it.length <= 512 }, "short lines")
        assertTrue(body.all { line -> line.none { it < ' ' || it == '\u007f' } }, "no control characters")
        assertTrue(body.none { it.first().isLowSurrogate() }, "no surrogate pair split")
        assertEquals(block, FakeShell.decodePrintfB(body.joinToString("")))
    }

    @Test
    fun missing_secret_skips_the_script() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        val outcomes = runner.run(
            scripts = listOf(script("a", "use \${x}", secretRefs = listOf("x"))),
            initialDirectory = null,
        )

        assertEquals(RunStatus.SKIPPED, outcomes.single().status)
        assertTrue(fake.sent.isEmpty(), "nothing is sent when a referenced secret is missing")
    }

    @Test
    fun exports_env_vars_before_body() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        runner.run(
            scripts = listOf(script("a", "run", envVars = mapOf("FOO" to "bar"))),
            initialDirectory = null,
        )

        val block = fake.sent.first { it.contains("run") }
        assertTrue(block.contains("export FOO='bar'"), "env exported: $block")
        assertTrue(block.indexOf("export FOO=") < block.indexOf("run"), "export precedes body")
    }

    @Test
    fun fire_and_forget_does_not_wait_for_a_sentinel() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        val outcomes = runner.run(
            scripts = listOf(script("a", "tail -f log", ScriptBehavior(waitForCompletion = false))),
            initialDirectory = null,
        )

        assertEquals(RunStatus.SENT, outcomes.single().status)
        assertTrue(fake.sent.none { it.startsWith("printf ") }, "no completion sentinel for fire-and-forget")
    }

    @Test
    fun abort_policy_stops_the_chain_on_failure() = runTest {
        val out = newOut()
        val fake = FakeShell(out) { cmd -> if (cmd.contains("boom")) 7 else 0 }
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        val outcomes = runner.run(
            scripts = listOf(
                script("a", "boom", ScriptBehavior(onFailure = ScriptFailurePolicy.ABORT)),
                script("b", "never"),
            ),
            initialDirectory = null,
        )

        assertEquals(RunStatus.FAILED, outcomes[0].status)
        assertEquals(7, outcomes[0].exitCode)
        assertEquals(RunStatus.ABORTED, outcomes[1].status)
        assertTrue(fake.sent.none { it.contains("never") }, "the aborted script is never sent")
    }

    @Test
    fun continue_policy_runs_the_rest_after_a_failure() = runTest {
        val out = newOut()
        val fake = FakeShell(out) { cmd -> if (cmd.contains("boom")) 1 else 0 }
        val runner = ScriptRunner(ShellIo(fake, out), { null })

        val outcomes = runner.run(
            scripts = listOf(
                script("a", "boom", ScriptBehavior(onFailure = ScriptFailurePolicy.CONTINUE)),
                script("b", "after"),
            ),
            initialDirectory = null,
        )

        assertEquals(RunStatus.FAILED, outcomes[0].status)
        assertEquals(RunStatus.SENT, outcomes[1].status)
        assertTrue(fake.sent.any { it.contains("after") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun expect_waits_for_the_pattern_before_sending() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val runner = ScriptRunner(ShellIo(fake, out), { null })
        val s = script("a", "secret-answer", ScriptBehavior(expectPattern = "login:", waitForCompletion = false))

        val job = launch(UnconfinedTestDispatcher(testScheduler)) { runner.run(listOf(s), null) }

        // The runner is parked on the expect pattern: nothing has been sent yet.
        assertTrue(fake.sent.none { it.contains("secret-answer") })
        out.emit("please login: ")
        job.join()
        assertTrue(fake.sent.any { it.contains("secret-answer") }, "body is sent once the pattern arrives")
    }

    @Test
    fun start_automation_selects_phases_in_order_and_skips_the_rest() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            scripts = listOf(
                script("post", "post-init", phase = ScriptPhase.POST_INIT),
                script("start", "on-start", phase = ScriptPhase.ON_SHELL_START),
                script("off", "disabled", enabled = false),
                script("demand", "manual", phase = ScriptPhase.ON_DEMAND),
                script("recon", "reconnect", phase = ScriptPhase.ON_RECONNECT),
                script("local", "local", phase = ScriptPhase.PRE_CONNECT_LOCAL),
            ),
        )
        val resolved = ResolvedConnection(
            session = session,
            host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r")),
            endpoint = SshEndpoint("x", 22, "u"),
            auth = HostAuth.Password("r"),
            appearance = TerminalAppearance(),
            jumps = emptyList(),
        )

        // The automation waits for the shell's first output (its prompt) before
        // sending, so make the fake shell announce one.
        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onShellReady(ShellIo(fake, out), resolved)

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'", "on-start", "post-init"), commands)
    }

    private fun reconnectResolved(session: Session) = ResolvedConnection(
        session = session,
        host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r")),
        endpoint = SshEndpoint("x", 22, "u"),
        auth = HostAuth.Password("r"),
        appearance = TerminalAppearance(),
        jumps = emptyList(),
    )

    @Test
    fun on_reconnect_restore_cd_only_replays_just_the_cd() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        // No ON_RECONNECT script: the session defaults to RESTORE_CD_ONLY.
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            scripts = listOf(
                script("start", "on-start", phase = ScriptPhase.ON_SHELL_START),
                script("post", "post-init", phase = ScriptPhase.POST_INIT),
            ),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onReconnected(ShellIo(fake, out), reconnectResolved(session))

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'"), commands, "only the working directory is restored")
    }

    @Test
    fun on_reconnect_rerun_all_replays_the_whole_chain() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            scripts = listOf(
                script("start", "on-start", phase = ScriptPhase.ON_SHELL_START),
                script("post", "post-init", phase = ScriptPhase.POST_INIT),
                SessionScript(
                    id = "recon", label = "recon", phase = ScriptPhase.ON_RECONNECT,
                    body = "reattach", reconnectBehavior = ReconnectBehavior.RERUN_ALL,
                ),
            ),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onReconnected(ShellIo(fake, out), reconnectResolved(session))

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'", "on-start", "post-init", "reattach"), commands)
    }

    @Test
    fun on_reconnect_none_replays_nothing() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            scripts = listOf(
                script("start", "on-start", phase = ScriptPhase.ON_SHELL_START),
                SessionScript(
                    id = "recon", label = "recon", phase = ScriptPhase.ON_RECONNECT,
                    body = "", reconnectBehavior = ReconnectBehavior.NONE,
                ),
            ),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onReconnected(ShellIo(fake, out), reconnectResolved(session))

        assertTrue(fake.sent.isEmpty(), "NONE runs nothing on reconnect")
    }

    @Test
    fun a_fresh_agent_session_runs_the_start_chain_without_a_multiplexer() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            resilienceLevel = ResilienceLevel.AGENT,
            scripts = listOf(
                script("start", "on-start", phase = ScriptPhase.ON_SHELL_START),
                script("post", "post-init", phase = ScriptPhase.POST_INIT),
                script("recon", "reconnect", phase = ScriptPhase.ON_RECONNECT),
            ),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap()))
            .onAgentSessionCreated(ShellIo(fake, out), reconnectResolved(session), afterDrop = false)

        // The agent's PTY already survives drops: no tmux/screen probe or attach.
        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'", "on-start", "post-init"), commands)
    }

    @Test
    fun an_agent_session_lost_in_a_drop_is_rebuilt_per_reconnect_behavior() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        // No ON_RECONNECT script: the session defaults to RESTORE_CD_ONLY.
        val session = Session(
            id = "s", name = "n", hostId = "h", initialDirectory = "/work",
            resilienceLevel = ResilienceLevel.AGENT,
            scripts = listOf(script("start", "on-start", phase = ScriptPhase.ON_SHELL_START)),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap()))
            .onAgentSessionCreated(ShellIo(fake, out), reconnectResolved(session), afterDrop = true)

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'"), commands)
    }

    @Test
    fun a_library_reference_runs_the_library_content_in_the_session_phase() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val library = LibraryScript(id = "lib", name = "arrancar", body = "./run.sh", envVars = mapOf("MODE" to "prod"))
        val session = Session(
            id = "s", name = "n", hostId = "h",
            scripts = listOf(
                SessionScript(id = "ref", label = "stale", phase = ScriptPhase.POST_INIT, body = "stale", libraryScriptId = "lib"),
                SessionScript(id = "gone", label = "gone", body = "never", libraryScriptId = "missing"),
            ),
        )
        val config = TitanConfig(
            hosts = listOf(Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r"))),
            sessions = listOf(session),
            scripts = listOf(library),
        )

        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onShellReady(ShellIo(fake, out), config.resolve(session))

        val log = fake.log()
        assertTrue(log.contains("export MODE='prod'\n./run.sh"), "library content runs: $log")
        assertFalse(log.contains("stale"), "the reference's own body is ignored: $log")
        assertFalse(log.contains("never"), "a dangling reference does not run: $log")
    }

    @Test
    fun on_demand_sends_the_script_without_a_completion_sentinel() = runTest {
        val out = newOut()
        val fake = FakeShell(out)
        val script = script("demand", "deploy \${token}", phase = ScriptPhase.ON_DEMAND, secretRefs = listOf("token"))

        StartScriptAutomation(FakeSecretStore(mapOf("token" to "s3cret".encodeToByteArray())))
            .runOnDemand(ShellIo(fake, out), script)

        assertEquals(listOf("deploy s3cret"), fake.hiddenBlocks)
        assertEquals(2, fake.sent.size, "the hidden-input line and its block, no printf sentinel: ${fake.sent}")
        assertTrue(fake.sent.none { it.startsWith("printf ") })
    }
}
