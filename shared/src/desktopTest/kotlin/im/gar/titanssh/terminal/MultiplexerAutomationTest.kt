package im.gar.titanssh.terminal

import im.gar.titanssh.config.Host
import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.config.ResilienceLevel
import im.gar.titanssh.config.ResolvedConnection
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * Level-2 coverage ([[Resiliencia nivel 2 auto-tmux o screen]]): a session set to
 * `AUTO_MULTIPLEXER` wraps its shell in tmux/screen when present (attach-or-
 * create), skips replaying scripts when it re-attaches to a live session, and
 * degrades to level-1 behavior when no multiplexer exists. Uses a fake shell that
 * answers both the multiplexer probes and the script-completion sentinels.
 */
class MultiplexerAutomationTest {

    /**
     * A shell that plays the remote for two kinds of sentinel `printf`s: the
     * multiplexer probes (`TITANMUX_…`, answered with a configured value from the
     * inner command) and the ScriptRunner completion sentinel (`__TITAN_…__`,
     * answered with exit 0).
     */
    private class FakeShell(
        private val out: MutableSharedFlow<String>,
        private val detectValue: String,
        private val existsValue: String,
    ) : SshShell {
        val sent = mutableListOf<String>()
        override val output: Flow<ByteArray> = MutableSharedFlow()
        override suspend fun send(data: ByteArray) {
            val text = data.decodeToString()
            sent += text
            MUX.find(text)?.let { m ->
                val token = m.groupValues[1]
                val inner = m.groupValues[2]
                val value = when {
                    inner.contains("command -v tmux") -> detectValue
                    inner.contains("has-session") || inner.contains("screen -ls") -> existsValue
                    else -> "none"
                }
                out.tryEmit("$token:$value:$token\n")
                return
            }
            SENTINEL.find(text)?.let { m ->
                out.tryEmit("${m.groupValues[1]}:0:${m.groupValues[1]}\n")
            }
        }
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun close() {}

        /** Commands sent to the shell, excluding the internal probe/sentinel printfs. */
        fun commands(): List<String> = sent.map { it.trim() }.filterNot { it.startsWith("printf ") }
        fun log(): String = sent.joinToString("")

        companion object {
            val MUX = Regex("""'(TITANMUX_[0-9a-f]+)' "\$\((.*)\)" '\1'""")
            val SENTINEL = Regex("""'(__TITAN_[0-9a-f]+__)' "\$\?" '\1'""")
        }
    }

    private class EmptySecretStore : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) {}
        override suspend fun get(ref: SecretRef): ByteArray? = null
        override suspend fun remove(ref: SecretRef) {}
        override suspend fun contains(ref: SecretRef): Boolean = false
    }

    private fun newOut() = MutableSharedFlow<String>(replay = 8, extraBufferCapacity = 64)

    private fun session(level: ResilienceLevel) = Session(
        id = "s",
        name = "n",
        hostId = "h",
        initialDirectory = "/work",
        resilienceLevel = level,
        scripts = listOf(
            SessionScript(id = "sc", label = "sc", phase = ScriptPhase.ON_SHELL_START, body = "on-start"),
        ),
    )

    private fun resolved(session: Session) = ResolvedConnection(
        session = session,
        host = Host(id = "h", alias = "h", hostname = "x", username = "u", auth = HostAuth.Password("r")),
        endpoint = SshEndpoint("x", 22, "u"),
        auth = HostAuth.Password("r"),
        appearance = TerminalAppearance(),
        proxyJump = null,
    )

    private fun automation() = StartScriptAutomation(EmptySecretStore(), multiplexerSettleMillis = 0)

    @Test
    fun fresh_tmux_session_is_created_and_start_scripts_run_inside() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "tmux", existsValue = "no")
        out.tryEmit("$ ")

        automation().onShellReady(ShellIo(fake, out), resolved(session(ResilienceLevel.AUTO_MULTIPLEXER)))

        val commands = fake.commands()
        assertTrue(
            commands.any { it == "exec tmux new-session -A -s 'titan-s'" },
            "it enters the named tmux session: $commands",
        )
        assertTrue(commands.any { it == "cd -- '/work'" }, "cd runs in the fresh session")
        assertTrue(fake.log().contains("on-start"), "start scripts run in the fresh session")
    }

    @Test
    fun existing_tmux_session_reattaches_without_rerunning_scripts() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "tmux", existsValue = "yes")
        out.tryEmit("$ ")

        automation().onShellReady(ShellIo(fake, out), resolved(session(ResilienceLevel.AUTO_MULTIPLEXER)))

        val commands = fake.commands()
        assertTrue(commands.any { it == "exec tmux new-session -A -s 'titan-s'" }, "it re-attaches")
        assertFalse(fake.log().contains("cd -- "), "cd is not re-run on an existing session")
        assertFalse(fake.log().contains("on-start"), "scripts are not re-run on an existing session")
    }

    @Test
    fun no_multiplexer_falls_back_to_plain_scripts() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "none", existsValue = "no")
        out.tryEmit("$ ")

        automation().onShellReady(ShellIo(fake, out), resolved(session(ResilienceLevel.AUTO_MULTIPLEXER)))

        val commands = fake.commands()
        assertFalse(commands.any { it.startsWith("exec ") }, "no multiplexer is entered")
        assertTrue(commands.any { it == "cd -- '/work'" }, "it still runs the start chain plainly")
        assertTrue(fake.log().contains("on-start"))
    }

    @Test
    fun screen_is_used_when_only_screen_is_present() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "screen", existsValue = "no")
        out.tryEmit("$ ")

        automation().onShellReady(ShellIo(fake, out), resolved(session(ResilienceLevel.AUTO_MULTIPLEXER)))

        assertTrue(
            fake.commands().any { it == "exec screen -xRR -S 'titan-s'" },
            "it enters a named screen session: ${fake.commands()}",
        )
    }

    @Test
    fun reconnect_reattaches_to_live_multiplexer_without_replay() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "tmux", existsValue = "yes")
        out.tryEmit("$ ")

        automation().onReconnected(ShellIo(fake, out), resolved(session(ResilienceLevel.AUTO_MULTIPLEXER)))

        val commands = fake.commands()
        assertTrue(commands.any { it == "exec tmux new-session -A -s 'titan-s'" }, "reconnect re-attaches")
        assertFalse(fake.log().contains("cd -- "), "no replay when the multiplexer session survived")
    }

    @Test
    fun base_level_never_enters_a_multiplexer() = runTest {
        val out = newOut()
        val fake = FakeShell(out, detectValue = "tmux", existsValue = "no")
        out.tryEmit("$ ")

        automation().onShellReady(ShellIo(fake, out), resolved(session(ResilienceLevel.BASE)))

        assertFalse(fake.commands().any { it.startsWith("exec ") }, "BASE stays on a plain shell")
        assertTrue(fake.commands().any { it == "cd -- '/work'" })
    }
}
