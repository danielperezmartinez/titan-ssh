package im.gar.titanssh.secret

import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.ssh.SshCredentials
import im.gar.titanssh.terminal.CredentialResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SecretProvisionerTest {

    // Honors the SecretStore contract: it copies the plaintext in and out, so it
    // never retains the caller's buffer (which SecretProvisioner wipes after put).
    private class FakeSecretStore : SecretStore {
        val map = mutableMapOf<String, ByteArray>()
        override suspend fun put(ref: SecretRef, secret: ByteArray) { map[ref.value] = secret.copyOf() }
        override suspend fun get(ref: SecretRef): ByteArray? = map[ref.value]?.copyOf()
        override suspend fun remove(ref: SecretRef) { map.remove(ref.value) }
        override suspend fun contains(ref: SecretRef): Boolean = map.containsKey(ref.value)
    }

    private val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n"

    @Test
    fun saves_password_as_utf8() = runTest {
        val store = FakeSecretStore()
        SecretProvisioner(store).savePassword(SecretRef("casa.password"), "s3cret".toCharArray())
        assertEquals("s3cret", store.get(SecretRef("casa.password"))?.decodeToString())
    }

    @Test
    fun empty_password_is_rejected() = runTest {
        assertFailsWith<SecretProvisioner.InvalidMaterial> {
            SecretProvisioner(FakeSecretStore()).savePassword(SecretRef("casa.password"), CharArray(0))
        }
    }

    @Test
    fun imports_private_key_with_passphrase() = runTest {
        val store = FakeSecretStore()
        SecretProvisioner(store).importPrivateKey(
            keyRef = SecretRef("casa.key"),
            pem = pem.toCharArray(),
            passphraseRef = SecretRef("casa.key.pass"),
            passphrase = "pp".toCharArray(),
        )
        assertEquals(pem, store.get(SecretRef("casa.key"))?.decodeToString())
        assertEquals("pp", store.get(SecretRef("casa.key.pass"))?.decodeToString())
    }

    @Test
    fun imports_private_key_without_passphrase() = runTest {
        val store = FakeSecretStore()
        SecretProvisioner(store).importPrivateKey(keyRef = SecretRef("casa.key"), pem = pem.toCharArray())
        assertEquals(pem, store.get(SecretRef("casa.key"))?.decodeToString())
        assertNull(store.get(SecretRef("casa.key.pass")))
    }

    @Test
    fun non_pem_text_is_rejected() = runTest {
        assertFailsWith<SecretProvisioner.InvalidMaterial> {
            SecretProvisioner(FakeSecretStore()).importPrivateKey(
                keyRef = SecretRef("casa.key"),
                pem = "not a key".toCharArray(),
            )
        }
    }

    @Test
    fun passphrase_without_a_ref_is_rejected() = runTest {
        assertFailsWith<SecretProvisioner.InvalidMaterial> {
            SecretProvisioner(FakeSecretStore()).importPrivateKey(
                keyRef = SecretRef("casa.key"),
                pem = pem.toCharArray(),
                passphraseRef = null,
                passphrase = "pp".toCharArray(),
            )
        }
    }

    @Test
    fun has_and_remove_track_the_store() = runTest {
        val store = FakeSecretStore()
        val provisioner = SecretProvisioner(store)
        assertFalse(provisioner.has(SecretRef("casa.password")))
        provisioner.savePassword(SecretRef("casa.password"), "x".toCharArray())
        assertTrue(provisioner.has(SecretRef("casa.password")))
        provisioner.remove(SecretRef("casa.password"))
        assertFalse(provisioner.has(SecretRef("casa.password")))
    }

    @Test
    fun provisioned_material_resolves_end_to_end() = runTest {
        // Write side (provisioner) and read side (CredentialResolver) agree: what
        // the editor stores is exactly what the connection materializes.
        val store = FakeSecretStore()
        val provisioner = SecretProvisioner(store)
        provisioner.savePassword(SecretRef("pw"), "hunter2".toCharArray())
        provisioner.importPrivateKey(
            keyRef = SecretRef("key"),
            pem = pem.toCharArray(),
            passphraseRef = SecretRef("key.pass"),
            passphrase = "pp".toCharArray(),
        )
        val resolver = CredentialResolver(store)

        val password = resolver.resolve(HostAuth.Password("pw"))
        assertTrue(password is SshCredentials.Password)
        assertEquals("hunter2", String(password.password))

        val key = resolver.resolve(HostAuth.SoftwareKey(secretRef = "key", passphraseRef = "key.pass"))
        assertTrue(key is SshCredentials.PrivateKey)
        assertEquals(pem, String(key.privateKeyPem))
        assertEquals("pp", key.passphrase?.let { String(it) })
    }
}
