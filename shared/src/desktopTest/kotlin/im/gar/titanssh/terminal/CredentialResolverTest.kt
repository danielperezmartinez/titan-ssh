package im.gar.titanssh.terminal

import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SecretStore
import im.gar.titanssh.secret.SshKeyType
import im.gar.titanssh.ssh.SshCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CredentialResolverTest {

    private class FakeSecretStore(initial: Map<String, ByteArray> = emptyMap()) : SecretStore {
        private val map = initial.toMutableMap()
        override suspend fun put(ref: SecretRef, secret: ByteArray) { map[ref.value] = secret }
        override suspend fun get(ref: SecretRef): ByteArray? = map[ref.value]
        override suspend fun remove(ref: SecretRef) { map.remove(ref.value) }
        override suspend fun contains(ref: SecretRef): Boolean = map.containsKey(ref.value)
    }

    @Test
    fun resolves_password() = runTest {
        val store = FakeSecretStore(mapOf("pw" to "s3cret".encodeToByteArray()))
        val creds = CredentialResolver(store).resolve(HostAuth.Password("pw"))
        assertTrue(creds is SshCredentials.Password)
        assertEquals("s3cret", String(creds.password))
    }

    @Test
    fun resolves_software_key_with_passphrase() = runTest {
        val store = FakeSecretStore(
            mapOf(
                "key" to "PEM-BODY".encodeToByteArray(),
                "pass" to "pp".encodeToByteArray(),
            ),
        )
        val creds = CredentialResolver(store).resolve(
            HostAuth.SoftwareKey(SshKeyType.ED25519, secretRef = "key", passphraseRef = "pass"),
        )
        assertTrue(creds is SshCredentials.PrivateKey)
        assertEquals("PEM-BODY", String(creds.privateKeyPem))
        assertEquals("pp", creds.passphrase?.let { String(it) })
    }

    @Test
    fun resolves_software_key_without_passphrase() = runTest {
        val store = FakeSecretStore(mapOf("key" to "PEM".encodeToByteArray()))
        val creds = CredentialResolver(store).resolve(HostAuth.SoftwareKey(secretRef = "key"))
        assertTrue(creds is SshCredentials.PrivateKey)
        assertNull(creds.passphrase)
    }

    @Test
    fun resolves_hardware_key_without_touching_store() = runTest {
        val creds = CredentialResolver(FakeSecretStore()).resolve(HostAuth.HardwareKey(alias = "titan-key"))
        assertTrue(creds is SshCredentials.HardwareKey)
        assertEquals("titan-key", creds.keyAlias)
    }

    @Test
    fun missing_secret_fails_clearly() = runTest {
        assertFailsWith<CredentialResolver.MissingSecret> {
            CredentialResolver(FakeSecretStore()).resolve(HostAuth.Password("absent"))
        }
    }
}
