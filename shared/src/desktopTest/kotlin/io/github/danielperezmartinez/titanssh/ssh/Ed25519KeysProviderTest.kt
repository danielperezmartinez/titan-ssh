package io.github.danielperezmartinez.titanssh.ssh

import java.security.Key
import java.security.KeyFactorySpi
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.spec.KeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils

/**
 * Reproduces Android 16's Conscrypt, whose `Ed25519` key factory comes first and
 * hands out keys named by their OID, and checks [Ed25519KeysProvider] gives sshj
 * keys it recognises again.
 */
class Ed25519KeysProviderTest {

    private var savedSshjProvider: String? = null

    @BeforeTest
    fun likeAndroid() {
        // sshj on desktop forces BC; on Android it picks from the JCE list (SshAndroidCrypto).
        savedSshjProvider = SecurityUtils.getSecurityProvider()
        SecurityUtils.setSecurityProvider(null)
        SecurityUtils.setRegisterBouncyCastle(false)
        Security.insertProviderAt(FakeConscrypt(), 1)
    }

    @AfterTest
    fun restore() {
        Security.removeProvider(Ed25519KeysProvider.NAME)
        Security.removeProvider(FakeConscrypt.NAME)
        SecurityUtils.setSecurityProvider(savedSshjProvider)
    }

    @Test
    fun without_it_sshj_cannot_tell_the_platform_key_apart() {
        assertEquals(KeyType.UNKNOWN, KeyType.fromKey(Ed25519KeyFactory.getPublicKey(RAW)))
    }

    @Test
    fun with_it_an_ed25519_host_key_is_recognised_and_encoded() {
        Ed25519KeysProvider.install()

        val key = Ed25519KeyFactory.getPublicKey(RAW)

        assertEquals(KeyType.ED25519, KeyType.fromKey(key))
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        assertContentEquals(RAW, blob.copyOfRange(blob.size - RAW.size, blob.size))
    }

    @Test
    fun installing_twice_adds_it_once() {
        Ed25519KeysProvider.install()
        Ed25519KeysProvider.install()

        assertEquals(1, Security.getProviders().count { it.name == Ed25519KeysProvider.NAME })
        assertEquals(Ed25519KeysProvider.NAME, Security.getProviders().first().name)
    }

    private companion object {
        /**
         * A real Ed25519 public key's 32 bytes. Arbitrary bytes will not do: a
         * key factory that rejects them lets the JCE fall through to the next
         * provider, which would hide what this test checks.
         */
        val RAW: ByteArray = KeyPairGenerator.getInstance("Ed25519", "SunEC").generateKeyPair()
            .public.encoded.takeLast(32).toByteArray()
    }
}

/** Stands in for Conscrypt: an `Ed25519` key factory whose keys report the OID. */
class FakeConscrypt : Provider(NAME, 1.0, "test stand-in for Conscrypt") {
    init {
        put("KeyFactory.Ed25519", FakeConscryptKeyFactory::class.java.name)
    }

    companion object {
        const val NAME = "FakeConscrypt"
    }
}

class FakeConscryptKeyFactory : KeyFactorySpi() {
    override fun engineGeneratePublic(keySpec: KeySpec): PublicKey {
        val encoded = (keySpec as X509EncodedKeySpec).encoded
        return object : PublicKey {
            override fun getAlgorithm() = "1.3.101.112"
            override fun getFormat() = "X.509"
            override fun getEncoded() = encoded
        }
    }

    override fun engineGeneratePrivate(keySpec: KeySpec): PrivateKey = throw UnsupportedOperationException()

    override fun <T : KeySpec> engineGetKeySpec(key: Key, keySpec: Class<T>): T = throw UnsupportedOperationException()

    override fun engineTranslateKey(key: Key): Key = throw UnsupportedOperationException()
}
