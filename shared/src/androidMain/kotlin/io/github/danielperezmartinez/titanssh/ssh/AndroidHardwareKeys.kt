package io.github.danielperezmartinez.titanssh.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Android hardware-backed SSH signing keys (ADR-0005). Keys are EC P-256
 * generated **non-exportable** in the Android Keystore (StrongBox when the
 * device offers it); they sign the SSH auth challenge via JCE without their
 * private material ever leaving the secure hardware.
 *
 * EC P-256 (`ecdsa-sha2-nistp256`) is used because it is the key type Android
 * Keystore signs with broad device support; ed25519 in Keystore is not portable
 * across devices yet (ADR-0005 lists P-256 as the accepted alternative).
 *
 * Call [install] once on startup so the shared engine can resolve
 * [SshCredentials.HardwareKey] aliases to their Keystore handles.
 */
object AndroidHardwareKeys {

    /** Registers the resolver the shared engine uses to sign with a Keystore key. */
    fun install() {
        HardwareKeyRegistry.resolver = { alias -> load(alias) }
    }

    /**
     * Ensures a hardware signing key exists under [alias], creating a
     * non-exportable EC P-256 key if absent, and returns its OpenSSH public key
     * line to enroll in the server's `authorized_keys`.
     */
    fun ensureKey(alias: String, preferStrongBox: Boolean = true): String {
        val existing = load(alias)
        val publicKey = existing?.publicKey ?: generate(alias, preferStrongBox)
        return sshPublicKeyOpenSshLine(publicKey)
    }

    private fun load(alias: String): DelegatedKeyMaterial? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return null
        return DelegatedKeyMaterial(publicKey, privateKey)
    }

    private fun generate(alias: String, preferStrongBox: Boolean): java.security.PublicKey {
        fun build(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        return try {
            generator.initialize(build(preferStrongBox))
            generator.generateKeyPair().public
        } catch (e: StrongBoxUnavailableException) {
            // Device has no StrongBox; fall back to TEE-backed Keystore.
            generator.initialize(build(strongBox = false))
            generator.generateKeyPair().public
        }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
