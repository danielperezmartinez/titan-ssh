package io.github.danielperezmartinez.titanssh.ssh

import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstallResult
import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstaller
import io.github.danielperezmartinez.titanssh.secret.SshKeyType
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Installs the TPM key [alias] of [keys] on the test host with its password,
 * as the host editor does, then logs in with only that key. Opt-in: needs
 * TITAN_SSH_TEST_HOST/USER/PASSWORD (a throwaway sshd), otherwise it skips.
 */
internal fun installAndLogIn(keys: DesktopKeyBackend, alias: String) {
    val hostname = System.getenv("TITAN_SSH_TEST_HOST")
    val user = System.getenv("TITAN_SSH_TEST_USER")
    val password = System.getenv("TITAN_SSH_TEST_PASSWORD")
    if (hostname == null || user == null || password == null) {
        return println("[integration] skipped: set TITAN_SSH_TEST_HOST/USER/PASSWORD (a throwaway sshd) to run")
    }
    val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
    HardwareKeyRegistry.resolver = { keys.load(it) }
    val line = sshPublicKeyOpenSshLine(keys.ensureKey(alias).publicKey)
    val auth = HostAuth.HardwareKey(SshKeyType.ECDSA_P256, alias)
    val host = Host(id = "tpm", alias = "", hostname = hostname, port = port, username = user, auth = auth)

    val result = runBlocking {
        KeyInstaller(createSshConnector(), InMemoryKnownHostsStore()) { SshCredentials.HardwareKey(alias) }
            .install(host, emptyList(), password.toCharArray(), line, auth, askHostKey = { it.accept() })
    }
    println("[tpm] install -> $result")
    assertIs<KeyInstallResult.Installed>(result)

    // And a plain connection with only the TPM key, as a session would open it.
    runBlocking {
        val session = createSshConnector().connect(
            endpoint = SshEndpoint(hostname, port, user),
            credentials = SshCredentials.HardwareKey(alias),
            hostKeyVerifier = { true },
            keepAliveSeconds = 0,
        )
        assertEquals(SshConnectionState.CONNECTED, session.state.value)
        println("[tpm] logged in with the TPM key '$alias'")
        session.close()
    }
}
