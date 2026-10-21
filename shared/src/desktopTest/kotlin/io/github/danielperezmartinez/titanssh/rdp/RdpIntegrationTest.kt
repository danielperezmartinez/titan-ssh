package io.github.danielperezmartinez.titanssh.rdp

import io.github.danielperezmartinez.titanssh.ssh.SshCredentials
import io.github.danielperezmartinez.titanssh.ssh.SshEndpoint
import io.github.danielperezmartinez.titanssh.ssh.SshSession
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The embedded RDP client end to end (ADR-0028): SSH connection, `direct-tcpip`
 * channel, the native engine through JNI, certificate prompt, NLA and frames.
 * Opt-in against the `tools/test-rdp` container; skips unless these are set:
 *
 * - TITAN_RDP_TEST_LIBRARY: the built `libtitan_rdp` (rdp/target/release).
 * - TITAN_SSH_TEST_HOST, TITAN_SSH_TEST_PORT, TITAN_SSH_TEST_USER and
 *   TITAN_SSH_TEST_KEY: the container's SSH server.
 * - TITAN_RDP_TEST_USER and TITAN_RDP_TEST_PASSWORD: the desktop's account.
 * - TITAN_RDP_TEST_PORT: the NLA server as the container sees it (3390 there).
 */
class RdpIntegrationTest {

    private val rdpPort = System.getenv("TITAN_RDP_TEST_PORT")?.toIntOrNull() ?: 3390

    private fun engine(): RdpEngine? {
        val library = System.getenv("TITAN_RDP_TEST_LIBRARY") ?: return null
        System.setProperty("titan.rdp.library", library)
        return when (val availability = loadRdpEngine()) {
            is RdpAvailability.Available -> availability.engine
            is RdpAvailability.Missing -> error("the RDP library did not load: ${availability.detail}")
        }
    }

    private fun connect(): SshSession? {
        val host = System.getenv("TITAN_SSH_TEST_HOST") ?: return null
        val user = System.getenv("TITAN_SSH_TEST_USER") ?: return null
        val key = System.getenv("TITAN_SSH_TEST_KEY") ?: return null
        val port = System.getenv("TITAN_SSH_TEST_PORT")?.toIntOrNull() ?: 22
        return runBlocking {
            createSshConnector().connect(
                endpoint = SshEndpoint(host, port, user),
                credentials = SshCredentials.PrivateKey(File(key).readText().toCharArray(), null),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )
        }
    }

    private class Setup(val engine: RdpEngine, val ssh: SshSession, val user: String, val password: String)

    private fun setup(): Setup? {
        val user = System.getenv("TITAN_RDP_TEST_USER")
        val password = System.getenv("TITAN_RDP_TEST_PASSWORD")
        val engine = engine()
        if (engine == null || user == null || password == null) {
            println("[rdp] skipped: set TITAN_RDP_TEST_LIBRARY/USER/PASSWORD and TITAN_SSH_TEST_* to run")
            return null
        }
        val ssh = connect() ?: return null.also { println("[rdp] skipped: no TITAN_SSH_TEST_*") }
        return Setup(engine, ssh, user, password)
    }

    private fun params(setup: Setup, password: String = setup.password) = RdpConnectParams(
        username = setup.user,
        password = password.encodeToByteArray(),
        domain = null,
        serverName = "localhost",
        width = 1024,
        height = 768,
        allowTlsWithoutNla = false,
        clientName = "titan-test",
    )

    private fun store(): RdpCertificateStore =
        FileRdpCertificateStore(File(Files.createTempDirectory("titan-rdp").toFile(), "rdp_certificates"))

    @Test
    fun nla_connects_and_paints_the_desktop() {
        val setup = setup() ?: return
        val certificates = store()
        runBlocking {
            val screen = RdpScreen()
            val channel = setup.ssh.openDirect("localhost", rdpPort)
            val run = async { screen.run(setup.engine, channel, params(setup), certificates, "test") }
            // First contact: the certificate waits for the user before any credential.
            val prompt = withTimeout(30_000) { screen.certificate.filterNotNull().first() }
            assertNull(prompt.previous)
            println("[rdp] certificate ${prompt.subject} ${formatFingerprint(prompt.sha256)}")
            prompt.accept()
            val painted = withTimeout(30_000) {
                screen.frame.filterNotNull().first { frame -> frame.version > 1 && frame.pixels.any { it != 0.toByte() } }
            }
            println("[rdp] desktop ${painted.width}x${painted.height}, version ${painted.version}")
            // The shadow server mirrors its own display, whatever size the client asks for.
            assertTrue(painted.width > 0 && painted.height > 0)
            assertEquals(prompt.sha256, certificates.trusted("test"), "the accepted certificate is remembered")
            // Input reaches the engine without breaking the session.
            screen.mouseMove(10, 10)
            screen.text("echo titan\n")
            run.cancel()
            setup.ssh.close()
        }
    }

    @Test
    fun a_trusted_certificate_is_not_asked_again() {
        val setup = setup() ?: return
        val certificates = store()
        runBlocking {
            repeat(2) { round ->
                val screen = RdpScreen()
                val asked = launch {
                    screen.certificate.filterNotNull().first().let {
                        assertEquals(0, round, "only the first connection asks")
                        it.accept()
                    }
                }
                val run = async {
                    screen.run(setup.engine, setup.ssh.openDirect("localhost", rdpPort), params(setup), certificates, "test")
                }
                withTimeout(30_000) { screen.frame.filterNotNull().first() }
                run.cancel()
                asked.cancel()
            }
            setup.ssh.close()
        }
    }

    @Test
    fun a_wrong_password_ends_with_auth() {
        val setup = setup() ?: return
        runBlocking {
            val screen = RdpScreen()
            val prompt = launch { screen.certificate.filterNotNull().first().accept() }
            val end = withTimeout(60_000) {
                screen.run(setup.engine, setup.ssh.openDirect("localhost", rdpPort), params(setup, "not-the-password"), store(), "test")
            }
            prompt.cancel()
            println("[rdp] wrong password: ${end.kind} ${end.message}")
            assertEquals("auth", end.kind)
            setup.ssh.close()
        }
    }

    @Test
    fun a_rejected_certificate_sends_no_credentials() {
        val setup = setup() ?: return
        runBlocking {
            val screen = RdpScreen()
            val prompt = launch { screen.certificate.filterNotNull().first().reject() }
            val end = withTimeout(30_000) {
                screen.run(setup.engine, setup.ssh.openDirect("localhost", rdpPort), params(setup), store(), "test")
            }
            prompt.cancel()
            assertEquals("certificate", end.kind)
            assertNotNull(end.message)
            assertTrue(screen.frame.value == null, "no desktop without trusting the server")
            setup.ssh.close()
        }
    }
}
