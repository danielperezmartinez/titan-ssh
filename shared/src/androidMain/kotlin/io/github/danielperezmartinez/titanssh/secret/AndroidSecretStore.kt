package io.github.danielperezmartinez.titanssh.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds the application [Context] the Android [SecretStore] needs. The Android
 * entrypoint calls [init] once on startup, before [createSecretStore] runs.
 *
 * A process-wide holder is used because `commonMain` cannot pass a [Context]
 * through the `expect fun createSecretStore()` seam.
 */
object AndroidSecretStoreContext {
    @Volatile
    private var appContext: Context? = null

    /** Records the application context. Safe to call more than once. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun require(): Context =
        appContext
            ?: error(
                "AndroidSecretStoreContext.init(context) must be called before createSecretStore()",
            )
}

actual fun createSecretStore(): SecretStore =
    AndroidKeystoreSecretStore(AndroidSecretStoreContext.require())

/**
 * [SecretStore] backed by the Android KeyStore (ADR-0001).
 *
 * A single AES-256-GCM master key lives non-exportable in the `AndroidKeyStore`
 * (hardware-backed when the device offers it). Each secret is encrypted with
 * that key and its ciphertext written to an app-private file; the plaintext
 * never touches disk. There is no plaintext persistence path.
 *
 * Optional biometric gating (`setUserAuthenticationRequired` + `BiometricPrompt`)
 * is a deliberate follow-up: it needs an Activity to prompt on, which belongs to
 * the UI layer, not this store.
 */
internal class AndroidKeystoreSecretStore(context: Context) : SecretStore {

    private val secretsDir: File = File(context.filesDir, SECRETS_DIR).apply { mkdirs() }

    override suspend fun put(ref: SecretRef, secret: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, masterKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret)

        // [ivLength][iv][ciphertext]
        val payload = ByteArray(1 + iv.size + ciphertext.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 1)
        ciphertext.copyInto(payload, destinationOffset = 1 + iv.size)

        writeAtomically(fileFor(ref), payload)
    }

    override suspend fun get(ref: SecretRef): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(ref)
        if (!file.exists()) return@withContext null
        try {
            val payload = file.readBytes()
            val ivLength = payload[0].toInt() and 0xFF
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecretStoreOperationFailed("Failed to read secret '${ref.value}'", e)
        }
    }

    override suspend fun remove(ref: SecretRef) = withContext(Dispatchers.IO) {
        fileFor(ref).delete()
        Unit
    }

    override suspend fun contains(ref: SecretRef): Boolean = fileFor(ref).exists()

    private fun fileFor(ref: SecretRef): File = File(secretsDir, ref.value)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // renameTo can fail if the target exists on some filesystems.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw SecretStoreOperationFailed("Failed to persist secret '${target.name}'")
            }
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        return generateMasterKey()
    }

    private fun generateMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "io.github.danielperezmartinez.titanssh.secretstore.master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SECRETS_DIR = "titan-secrets"
    }
}
