package io.github.danielperezmartinez.titanssh.secret

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createSecretStore(): SecretStore = DesktopSecretStore(SERVICE_NAMESPACE)

private const val SERVICE_NAMESPACE = "titan-ssh"

/**
 * [SecretStore] backed by the desktop OS secret store (ADR-0001) through
 * java-keyring: the Windows Credential Store (DPAPI-backed) on Windows and the
 * Secret Service (libsecret / desktop keyring) on Linux. macOS is out of scope
 * (ADR-0002) but the same wrapper would cover its Keychain.
 *
 * Fails closed: if no OS-backed keyring is available the store raises
 * [SecretStoreUnavailable] instead of persisting secrets in plaintext, so there
 * is no insecure fallback path.
 *
 * java-keyring stores strings, so secret bytes are Base64-encoded in transit;
 * the encoding is only a transport detail — the OS store holds the protected
 * value.
 *
 * @param namespace the service name secrets are grouped under in the OS store.
 */
internal class DesktopSecretStore(private val namespace: String) : SecretStore {

    private val keyring: Keyring by lazy {
        try {
            Keyring.create()
        } catch (e: BackendNotSupportedException) {
            throw SecretStoreUnavailable(
                "No OS-backed secret store is available on this desktop",
                e,
            )
        }
    }

    override suspend fun put(ref: SecretRef, secret: ByteArray) = withContext(Dispatchers.IO) {
        val encoded = Base64.getEncoder().encodeToString(secret)
        try {
            keyring.setPassword(namespace, ref.value, encoded)
        } catch (e: PasswordAccessException) {
            throw SecretStoreOperationFailed("Failed to store secret '${ref.value}'", e)
        }
    }

    override suspend fun get(ref: SecretRef): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = try {
            keyring.getPassword(namespace, ref.value)
        } catch (e: PasswordAccessException) {
            // java-keyring signals "no such entry" with this exception.
            return@withContext null
        }
        Base64.getDecoder().decode(encoded)
    }

    override suspend fun remove(ref: SecretRef) = withContext(Dispatchers.IO) {
        try {
            keyring.deletePassword(namespace, ref.value)
        } catch (e: PasswordAccessException) {
            // Deleting a missing entry is a no-op per the contract.
        }
    }

    override suspend fun contains(ref: SecretRef): Boolean = get(ref) != null
}
