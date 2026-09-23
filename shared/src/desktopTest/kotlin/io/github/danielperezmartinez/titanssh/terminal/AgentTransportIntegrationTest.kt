package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
            val t1 = AgentTransport(s1, agentId, path!!, { b -> out1.append(b.decodeToString()) }, 80, 24, 0)
            val j1 = launch { t1.run() }
            waitUntil { out1.text().isNotEmpty() }               // the prompt teed through
            t1.sendInput("echo $marker\n".encodeToByteArray())
            waitUntil { out1.text().contains(marker) }           // command output came back
            t1.close(); j1.join(); s1.close()

            // 2) Reconnect (fresh transport, replay from 0): the daemon survived, so
            //    the buffered history — including the marker — comes back with no input.
            val out2 = Acc()
            val s2 = connect(endpoint, creds)
            val t2 = AgentTransport(s2, agentId, path, { b -> out2.append(b.decodeToString()) }, 80, 24, 0)
            val j2 = launch { t2.run() }
            waitUntil { out2.text().contains(marker) }
            assertTrue(out2.text().contains(marker), "reconnect should replay the buffered history")
            t2.close(); j2.join()

            // Cleanup: stop the daemon (exact name, so we don't kill our own shell)
            // and remove the socket + the throwaway binary.
            s2.exec("pkill -x titan-agent; rm -f /run/user/1000/titan-agent.sock $path")
                .output.fold(0) { a, _ -> a }
            s2.close()
            println("[integration] agent transport verified end-to-end (marker replayed on reconnect)")
        }
    }
}
