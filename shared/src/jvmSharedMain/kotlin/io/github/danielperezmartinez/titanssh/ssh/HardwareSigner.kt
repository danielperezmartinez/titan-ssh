package io.github.danielperezmartinez.titanssh.ssh

import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * A public key plus a signing handle for its private key. For a hardware-backed
 * key the [privateKey] is a non-exportable OS-key-store handle (Android
 * Keystore): usable to sign via JCE, but its material never leaves the store.
 */
class DelegatedKeyMaterial(
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

/**
 * Platform bridge for [SshCredentials.HardwareKey]. The platform (Android)
 * installs a [resolver] that maps a key alias to its [DelegatedKeyMaterial] from
 * the OS key store; the engine, which lives in the shared JVM layer and cannot
 * touch the Android Keystore, calls [resolve].
 *
 * When no resolver is installed (e.g. desktop v1, no non-exportable key store),
 * resolution returns `null` and the engine reports
 * [SshHardwareKeyUnavailable] so the caller can fall back to a software key.
 */
object HardwareKeyRegistry {
    @Volatile
    var resolver: ((alias: String) -> DelegatedKeyMaterial?)? = null

    fun resolve(alias: String): DelegatedKeyMaterial? = resolver?.invoke(alias)
}

/**
 * The OpenSSH one-line public key (`<keytype> <base64>`) for [key], suitable for
 * a server's `authorized_keys`. Used to enroll a hardware key on the server.
 */
internal fun sshPublicKeyOpenSshLine(key: PublicKey): String =
    "${KeyType.fromKey(key)} ${java.util.Base64.getEncoder().encodeToString(sshPublicKeyBlob(key))}"

/**
 * sshj [KeyProvider] over a [DelegatedKeyMaterial]. sshj selects its signature
 * implementation from the key type and signs with the provided [PrivateKey] via
 * JCE, so a non-exportable Android Keystore key signs in hardware transparently.
 */
internal class DelegatedKeyProvider(
    private val material: DelegatedKeyMaterial,
) : KeyProvider {
    override fun getPrivate(): PrivateKey = material.privateKey
    override fun getPublic(): PublicKey = material.publicKey
    override fun getType(): KeyType = KeyType.fromKey(material.publicKey)
}
