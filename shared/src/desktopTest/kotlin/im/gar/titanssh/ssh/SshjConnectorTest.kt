package im.gar.titanssh.ssh

import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Headless coverage of the SSH engine: everything that does not need a live
 * server. The real handshake (auth, host-key TOFU, shell I/O) is verified
 * separately against a test host.
 */
class SshjConnectorTest {

    @Test
    fun factory_returns_a_connector() {
        assertNotNull(createSshConnector())
    }

    @Test
    fun fingerprint_has_the_standard_sha256_shape() {
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public

        val fingerprint = sshFingerprintSha256(key)

        assertTrue(fingerprint.startsWith("SHA256:"), "must carry the SHA256: prefix")
        val body = fingerprint.removePrefix("SHA256:")
        assertFalse(body.endsWith("="), "base64 must be unpadded")
        assertEquals(32, Base64.getDecoder().decode(body).size, "SHA-256 digest is 32 bytes")
    }

    @Test
    fun connecting_to_a_closed_port_fails_as_connect_error() = runTest {
        // Reserve then release a port so nothing is listening on it.
        val deadPort = ServerSocket(0).use { it.localPort }

        val connector = createSshConnector()

        assertFailsWith<SshConnectFailed> {
            connector.connect(
                endpoint = SshEndpoint("127.0.0.1", deadPort, "nobody"),
                credentials = SshCredentials.Password("irrelevant".toCharArray()),
                hostKeyVerifier = { true },
                keepAliveSeconds = 0,
            )
        }
    }
}
