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
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Full client↔agent loop against the real test host (ADR-0008): install
 * `titan-agent`, drive it with [AgentTransport] over a live SSH session, prove
 * that input reaches the remote shell and its output comes back, and that a
 * fresh transport asking for replay from 0 re-attaches to the surviving daemon
 * and gets the buffered history back. Opt-in (same env as the other integration
 * tests, plus TITAN_AGENT_BIN). Cleans up the host afterwards.
 */
class AgentTransportIntegrationTest {

    private class Acc {
        private val sb = StringBuilder()
        fun append(s: String) = synchronized(sb) { sb.append(s); Unit }
        fun text(): String = synchronized(sb) { sb.toString() }
    }

    private fun env(): Triple<SshEndpoint, () -> SshCredentials, File>? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val key = System.getenv("TITAN_SSH_TEST_KEY")
        val bin = System.getenv("TITAN_AGENT_BIN")
        if (host == null || user == null || key == null || bin == null) {
            println("[integration] agent-transport skipped: set TITAN_SSH_TEST_HOST/USER/KEY and TITAN_AGENT_BIN")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val pass = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        val creds = { SshCredentials.PrivateKey(File(key).readText().toCharArray(), pass) as SshCredentials }
        return Triple(SshEndpoint(host, port, user), creds, File(bin))
    }

    private suspend fun waitUntil(cond: () -> Boolean) = withTimeout(8_000) { while (!cond()) delay(20) }

    private suspend fun connect(endpoint: SshEndpoint, creds: () -> SshCredentials): SshSession =
        createSshConnector().connect(endpoint, creds(), { true }, keepAliveSeconds = 0)

    @Test
    fun drives_the_agent_and_replays_on_reconnect() {
        val (endpoint, creds, bin) = env() ?: return
        val bytes = bin.readBytes()
        val version = "itest-${System.currentTimeMillis()}"
        val agentId = "sess_tx_${System.currentTimeMillis()}"
        val marker = "TITAN_TX_${System.currentTimeMillis()}"

        runBlocking {
            val install = connect(endpoint, creds)
            val path = agentDeployer(version = version) { bytes }.ensureInstalled(install)
            assertNotNull(path, "agent should install")

            // 1) First attach: send input, see the shell run it.
            val out1 = Acc()
            val s1 = connect(endpoint, creds)
            var fresh1: Boolean? = null
            val t1 = AgentTransport(s1, agentId, path!!, { b -> out1.append(b.decodeToString()) }, 80, 24, 0,
                onAttached = { fresh1 = it })
            val j1 = launch { t1.run() }
            waitUntil { out1.text().isNotEmpty() }               // the prompt teed through
            assertEquals(true, fresh1, "the first attach creates the agent session")
            t1.sendInput("echo $marker\n".encodeToByteArray())
            waitUntil { out1.text().contains(marker) }           // command output came back
            t1.close(); j1.join(); s1.close()

            // 2) Reconnect (fresh transport, replay from 0): the daemon survived, so
            //    the buffered history — including the marker — comes back with no input.
            val out2 = Acc()
            val s2 = connect(endpoint, creds)
            var fresh2: Boolean? = null
            val t2 = AgentTransport(s2, agentId, path, { b -> out2.append(b.decodeToString()) }, 80, 24, 0,
                onAttached = { fresh2 = it })
            val j2 = launch { t2.run() }
            waitUntil { out2.text().contains(marker) }
            assertTrue(out2.text().contains(marker), "reconnect should replay the buffered history")
            assertEquals(false, fresh2, "a reconnect re-attaches to the live agent session")
            t2.close(); j2.join()

            // Cleanup: stop the daemon (exact name, so we don't kill our own shell)
            // and remove the socket + the throwaway binary.
            s2.exec("pkill -x titan-agent; rm -f /run/user/1000/titan-agent.sock $path")
                .output.fold(0) { a, _ -> a }
            s2.close()
            println("[integration] agent transport verified end-to-end (marker replayed on reconnect)")
        }
    }

    /**
     * [[Scripts de inicio por sesión sobre el agente]] on a real host: a level-3
     * tab runs its initial `cd` and start script once, when the agent creates the
     * PTY; a second tab on the same session (the app reopened) re-attaches and
     * runs nothing. The script appends to a file, so its line count is the
     * number of runs.
     */
    @Test
    fun session_tab_runs_start_scripts_only_on_a_fresh_agent_session() {
        val (endpoint, creds, bin) = env() ?: return
        val bytes = bin.readBytes()
        val stamp = System.currentTimeMillis()
        val version = "itest-$stamp"
        val runs = "/tmp/titan-itest-runs-$stamp"

        val host = Host(
            id = "h", alias = "test", hostname = endpoint.host, port = endpoint.port,
            username = endpoint.username, auth = HostAuth.SoftwareKey(secretRef = "unused-in-test"),
        )
        val session = Session(
            id = "sess_scripts_$stamp", name = "agent-scripts", hostId = "h",
            resilienceLevel = ResilienceLevel.AGENT,
            initialDirectory = "/tmp",
            scripts = listOf(
                SessionScript(
                    id = "sc1", label = "count runs", phase = ScriptPhase.ON_SHELL_START,
                    body = "echo \"PWD=\$(pwd)\"; echo ran >> $runs",
                    behavior = ScriptBehavior(waitForCompletion = true),
                ),
            ),
        )
        val resolved = ResolvedConnection(
            session = session, host = host, endpoint = endpoint, auth = host.auth,
            appearance = TerminalAppearance(), proxyJump = null,
        )

        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            fun newTab(id: String) = SessionTab(
                id = id,
                resolved = resolved,
                connector = createSshConnector(),
                credentials = { creds() },
                knownHostsStore = InMemoryKnownHostsStore(),
                scope = scope,
                automation = StartScriptAutomation(EmptySecretStore()),
                agentDeployer = agentDeployer(version = version) { bytes },
            ).also { tab -> scope.launch { tab.pendingHostKey.collect { it?.accept() } } }

            suspend fun runCount(): Int {
                val s = connect(endpoint, creds)
                val out = Acc()
                s.exec("cat $runs 2>/dev/null | wc -l").output.collect { out.append(it.decodeToString()) }
                s.close()
                return out.text().trim().toIntOrNull() ?: -1
            }

            try {
                // 1) Fresh agent session: the cd and the script run over INPUT frames.
                val first = newTab("tab-1")
                first.start()
                waitUntil(30_000) { first.text().contains("PWD=/tmp") }
                waitUntil(10_000) { runCount() == 1 }
                first.close()

                // 2) Same session again (the app reopened): the agent re-attaches,
                //    replays the screen, and nothing runs a second time.
                val second = newTab("tab-2")
                second.start()
                waitUntil(30_000) { second.text().contains("PWD=/tmp") } // the replayed history
                delay(3_000)
                assertEquals(1, runCount(), "a re-attach must not re-run the start scripts")
                second.close()
                println("[integration] level-3 start scripts ran once, not on re-attach")
            } finally {
                val s = connect(endpoint, creds)
                s.exec("pkill -x titan-agent; rm -f /run/user/1000/titan-agent.sock $runs ~/.local/share/titan-ssh/agent-$version-*")
                    .output.fold(0) { a, _ -> a }
                s.close()
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: suspend () -> Boolean) =
        withTimeout(timeoutMs) { while (!cond()) delay(100) }

    private fun SessionTab.text(): String {
        val snap = snapshot.value
        return (snap.scrollback + snap.screen).joinToString("\n") { line ->
            line.joinToString("") { it.char.toString() }
        }
    }

    private class EmptySecretStore : SecretStore {
        override suspend fun put(ref: SecretRef, secret: ByteArray) {}
        override suspend fun get(ref: SecretRef): ByteArray? = null
        override suspend fun remove(ref: SecretRef) {}
        override suspend fun contains(ref: SecretRef): Boolean = false
    }
}
