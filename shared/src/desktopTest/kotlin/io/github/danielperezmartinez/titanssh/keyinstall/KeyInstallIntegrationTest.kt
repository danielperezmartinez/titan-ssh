package io.github.danielperezmartinez.titanssh.keyinstall

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.ssh.InMemoryKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.KnownHostEntry
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import io.github.danielperezmartinez.titanssh.ssh.generateEd25519KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Installs keys on real sshd servers, one per case of the catalog (ADR-0030).
 * Opt-in: start the throwaway containers with
 * `tools/test-sshd/key-install-scenarios.sh up` and run with
 * `-PtitanKeyInstallTest=1`; otherwise every test skips.
 */
class KeyInstallIntegrationTest {

    private val enabled = System.getenv("TITAN_KEY_INSTALL_TEST") == "1"

    private fun host(port: Int, hostname: String = "127.0.0.1", jump: String? = null) = Host(
        id = "h$port", alias = "", hostname = hostname, port = port, username = "demo",
        auth = HostAuth.Password("unused"), proxyJumpHostId = jump,
    )

    private class Run(val result: KeyInstallResult, val asked: Int)

    private fun install(
        host: Host,
        jumps: List<Host> = emptyList(),
        password: String = "demo",
        store: InMemoryKnownHostsStore = InMemoryKnownHostsStore(),
        trust: Boolean = true,
        key: Pair<String, CharArray> = generateEd25519KeyPair("titan-ssh-test").let { it.publicLine to it.privateKeyPem },
    ): Run = runBlocking {
        var asked = 0
        val installer = KeyInstaller(createSshConnector(), store) { auth ->
            when (auth) {
                is HostAuth.SoftwareKey -> SshCredentials.PrivateKey(key.second.copyOf())
                else -> SshCredentials.Password("demo".toCharArray())
            }
        }
        val result = installer.install(
            host, jumps, password.toCharArray(), key.first, HostAuth.SoftwareKey(secretRef = "k"),
            askHostKey = { asked++; if (trust) it.accept() else it.reject() },
        )
        println("[key-install] ${host.hostname}:${host.port} -> $result")
        Run(result, asked)
    }

    private fun problemOf(run: Run): KeyInstallProblem = (run.result as KeyInstallResult.Failed).problem

    @Test
    fun installs_on_a_plain_server_and_does_not_duplicate() {
        if (!enabled) return println("[integration] skipped: run tools/test-sshd/key-install-scenarios.sh up and -PtitanKeyInstallTest=1")
        val key = generateEd25519KeyPair("titan-ssh-test").let { it.publicLine to it.privateKeyPem }
        val store = InMemoryKnownHostsStore()
        val first = install(host(2240), store = store, key = key)
        assertEquals(KeyInstallResult.Installed(false, "Unix", "~/.ssh/authorized_keys"), first.result)
        assertEquals(1, first.asked)
        val again = install(host(2240), store = store, key = key)
        assertEquals(KeyInstallResult.Installed(true, "Unix", "~/.ssh/authorized_keys"), again.result)
        assertEquals(0, again.asked)
    }

    @Test
    fun installs_through_keyboard_interactive_only() {
        if (!enabled) return
        assertIs<KeyInstallResult.Installed>(install(host(2242)).result)
    }

    @Test
    fun installs_through_a_jump_host() {
        if (!enabled) return
        val bastion = host(2240).copy(id = "bastion", alias = "bastion")
        val run = install(host(22, hostname = "ki-target", jump = "bastion"), jumps = listOf(bastion))
        assertIs<KeyInstallResult.Installed>(run.result)
        assertEquals(2, run.asked) // the bastion's key and the target's
    }

    @Test
    fun password_failures() {
        if (!enabled) return
        assertEquals(KeyInstallProblem.WRONG_PASSWORD, problemOf(install(host(2240), password = "wrong")))
        val noPassword = install(host(2241)).result as KeyInstallResult.Failed
        assertEquals(KeyInstallProblem.PASSWORD_NOT_OFFERED, noPassword.problem)
        assertEquals("El servidor ofrece: publickey", noPassword.detail)
    }

    @Test
    fun host_key_failures_stop_before_the_password() {
        if (!enabled) return
        assertEquals(KeyInstallProblem.HOST_KEY_DECLINED, problemOf(install(host(2240), trust = false)))
        val poisoned = InMemoryKnownHostsStore(listOf(KnownHostEntry("127.0.0.1", 2240, "ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5AAAAIOther")))
        assertEquals(KeyInstallProblem.HOST_KEY_CHANGED, problemOf(install(host(2240), store = poisoned)))
    }

    @Test
    fun write_failures() {
        if (!enabled) return
        assertEquals(KeyInstallProblem.READ_ONLY, problemOf(install(host(2244))))
        assertEquals(KeyInstallProblem.DISK_FULL, problemOf(install(host(2245))))
        assertEquals(KeyInstallProblem.SSH_DIR_NOT_OWNED, problemOf(install(host(2248))))
    }

    @Test
    fun copied_keys_the_server_does_not_take() {
        if (!enabled) return
        assertEquals(KeyInstallProblem.STRICT_MODES_HOME, problemOf(install(host(2243))))
        assertEquals(KeyInstallProblem.PUBKEY_DISABLED, problemOf(install(host(2246))))
        assertEquals(KeyInstallProblem.KEY_REJECTED, problemOf(install(host(2247))))
    }
}
