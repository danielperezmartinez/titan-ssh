package im.gar.titanssh.ssh

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Headless coverage of the hardware-signer delegation wiring. The Android
 * Keystore backend itself needs a device; here we prove the registry and the
 * sshj [DelegatedKeyProvider] adapter behave, using a software EC P-256 key that
 * stands in for the (identically shaped) Keystore handle.
 */
class DelegatedKeyProviderTest {

    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    @AfterTest
    fun clearResolver() {
        HardwareKeyRegistry.resolver = null
    }

    @Test
    fun provider_exposes_the_material_and_its_ssh_type() {
        val material = DelegatedKeyMaterial(keyPair.public, keyPair.private)
        val provider = DelegatedKeyProvider(material)

        assertSame(keyPair.public, provider.public)
        assertSame(keyPair.private, provider.private)
        assertEquals("ecdsa-sha2-nistp256", provider.type.toString())
    }

    @Test
    fun openssh_line_has_the_ecdsa_shape() {
        val line = sshPublicKeyOpenSshLine(keyPair.public)
        assertTrue(line.startsWith("ecdsa-sha2-nistp256 "), "type prefix")
        assertTrue(line.substringAfter(' ').isNotBlank(), "base64 body present")
    }

    @Test
    fun registry_returns_null_without_a_resolver_and_material_with_one() {
        assertNull(HardwareKeyRegistry.resolve("any"))

        val material = DelegatedKeyMaterial(keyPair.public, keyPair.private)
        HardwareKeyRegistry.resolver = { alias -> if (alias == "titan") material else null }

        assertSame(material, HardwareKeyRegistry.resolve("titan"))
        assertNull(HardwareKeyRegistry.resolve("other"))
    }
}
