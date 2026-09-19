package im.gar.titanssh.terminal

import im.gar.titanssh.config.Host
import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.config.ScriptBehavior
import im.gar.titanssh.config.ScriptFailurePolicy
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.Session
import im.gar.titanssh.config.SessionScript
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SecretStore
import im.gar.titanssh.ssh.SshEndpoint
import im.gar.titanssh.ssh.SshShell
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
        private val exitFor: (String) -> Int = { 0 },
    ) : SshShell {
        val sent = mutableListOf<String>()
        private var last = ""
        override val output: Flow<ByteArray> = MutableSharedFlow()
        override suspend fun send(data: ByteArray) {
            val text = data.decodeToString()
            sent += text
            val m = SENTINEL.find(text)
            if (m != null) {
                val token = m.groupValues[1]
                out.emit("$token:${exitFor(last)}:$token\n")
            } else {
                last = text.trim()
            }
        }
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() {}

        /** The whole conversation, for substring assertions. */
        fun log(): String = sent.joinToString("")

        companion object {
            val SENTINEL = Regex("""'(__TITAN_[0-9a-f]+__)' "\$\?" '\1'""")
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
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.COMPLETED), outcomes.map { it.status })
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

        val log = fake.log()
        assertTrue(log.contains("deploy s3cret to \${HOME}"), "secret substituted, shell var left alone: $log")
        assertFalse(log.contains("\${token}"))
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
        assertEquals(RunStatus.COMPLETED, outcomes[1].status)
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
            proxyJump = null,
        )

        // The automation waits for the shell's first output (its prompt) before
        // sending, so make the fake shell announce one.
        out.tryEmit("[user@host ~]$ ")
        StartScriptAutomation(FakeSecretStore(emptyMap())).onShellReady(ShellIo(fake, out), resolved)

        val commands = fake.sent.filterNot { it.startsWith("printf ") }.map { it.trim() }
        assertEquals(listOf("cd -- '/work'", "on-start", "post-init"), commands)
    }
}
