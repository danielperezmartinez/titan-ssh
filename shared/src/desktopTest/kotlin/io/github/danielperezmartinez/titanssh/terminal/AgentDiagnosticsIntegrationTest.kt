package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.Session
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
 * Level 3 unavailable on a real destination ([[Diagnóstico cuando el nivel 3 no
 * está disponible]]): the tab says why, runs a working shell instead, and a
 * missing binary is explained too. Opt-in, same env as
 * [AgentTransportIntegrationTest]. **It makes the agent's state directory
 * world-writable for a moment and stops the test version's daemon**, so point it
 * at a throwaway host (a sshd in Docker), not one whose agent is in use.
 */
class AgentDiagnosticsIntegrationTest {

    private fun env(): Triple<SshEndpoint, () -> SshCredentials, File>? {
        val host = System.getenv("TITAN_SSH_TEST_HOST")
        val user = System.getenv("TITAN_SSH_TEST_USER")
        val key = System.getenv("TITAN_SSH_TEST_KEY")
        val bin = System.getenv("TITAN_AGENT_BIN")
        if (host == null || user == null || key == null || bin == null) {
            println("[integration] agent-diagnostics skipped: set TITAN_SSH_TEST_HOST/USER/KEY and TITAN_AGENT_BIN")
            return null
        }
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        val pass = System.getenv("TITAN_SSH_TEST_PASSPHRASE")?.toCharArray()
        val creds = { SshCredentials.PrivateKey(File(key).readText().toCharArray(), pass) as SshCredentials }
        return Triple(SshEndpoint(host, port, user), creds, File(bin))
    }

    private fun resolved(endpoint: SshEndpoint): ResolvedConnection {
        val host = Host(id = "h", alias = "h", hostname = endpoint.host, username = endpoint.username, auth = HostAuth.Password("r"))
        return ResolvedConnection(
            session = Session(id = "diag_${System.currentTimeMillis()}", name = "diag", hostId = "h", resilienceLevel = ResilienceLevel.AGENT),
            host = host, endpoint = endpoint, auth = host.auth, appearance = TerminalAppearance(), proxyJump = null,
        )
    }

    private suspend fun run(endpoint: SshEndpoint, creds: () -> SshCredentials, command: String) {
        val s: SshSession = createSshConnector().connect(endpoint, creds(), { true }, keepAliveSeconds = 0)
        s.exec(command).output.fold(0) { a, _ -> a }
        s.close()
    }

    private fun withTab(
        endpoint: SshEndpoint,
        creds: () -> SshCredentials,
        deployer: AgentDeployer,
        block: suspend (SessionTab) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tab = SessionTab(
            id = "diag",
            resolved = resolved(endpoint),
            connector = createSshConnector(),
            credentials = { creds() },
            knownHostsStore = InMemoryKnownHostsStore(),
            scope = scope,
            automation = StartScriptAutomation(EmptySecretStore()),
            agentDeployer = deployer,
        )
        scope.launch { tab.pendingHostKey.collect { it?.accept() } }
        try {
            tab.start()
            block(tab)
        } finally {
            tab.close()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun an_unsafe_state_dir_is_explained_and_the_tab_falls_back_to_a_shell() {
        val (endpoint, creds, bin) = env() ?: return
        val bytes = bin.readBytes()
        val version = "itest-${System.currentTimeMillis()}"
        val stateDir = "~/.local/state/titan-ssh"
        runBlocking { run(endpoint, creds, "mkdir -p $stateDir && chmod 777 $stateDir") }
        try {
            withTab(endpoint, creds, agentDeployer(version = version, isStale = { false }) { bytes }) { tab ->
                waitUntil(30_000) { tab.resilience.value.issue != null && tab.resilience.value.level != null }
                val status = tab.resilience.value
                assertEquals(AgentDiagnostics.E_STATE_DIR, status.issue?.code, "got $status")
                assertTrue(status.level != EffectiveLevel.AGENT, "got $status")
                assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
                // The fallback shell works on the same tab.
                tab.sendBytes("echo DEGRADED_\$((40+2))\n".encodeToByteArray())
                waitUntil(10_000) { tab.text().contains("DEGRADED_42") }
                println("[integration] ${AgentDiagnostics.describe(status.issue!!)} → ${status.level}")
            }
        } finally {
            runBlocking {
                run(
                    endpoint, creds,
                    "chmod 700 $stateDir; for b in ~/.local/share/titan-ssh/agent-$version-*; do \"\$b\" --stop; done; " +
                        "rm -f ~/.local/share/titan-ssh/agent-$version-*",
                )
            }
        }
    }

    @Test
    fun a_destination_without_a_binary_is_explained() {
        val (endpoint, creds, _) = env() ?: return
        withTab(endpoint, creds, agentDeployer(version = "itest-none") { null }) { tab ->
            waitUntil(30_000) { tab.resilience.value.issue != null && tab.resilience.value.level != null }
            assertEquals(AgentDiagnostics.E_NO_BINARY, tab.resilience.value.issue?.code)
            assertEquals(TabPhase.CONNECTED, tab.status.value.phase)
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
