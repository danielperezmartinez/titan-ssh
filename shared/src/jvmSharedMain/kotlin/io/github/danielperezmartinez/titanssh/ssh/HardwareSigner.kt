package io.github.danielperezmartinez.titanssh.ssh

import com.hierynomus.sshj.key.KeyAlgorithm
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * A public key plus a signing handle for its private key. For a hardware-backed
 * key the [privateKey] is a non-exportable OS-key-store handle: an Android
 * Keystore key signs via JCE, a desktop TPM key is a [PlatformSigningKey]. Its
 * material never leaves the store.
 */
class DelegatedKeyMaterial(
    val publicKey: PublicKey,
    val privateKey: PrivateKey,
)

/**
 * Platform bridge for [SshCredentials.HardwareKey]. The platform (Android
 * Keystore, desktop TPM) installs a [resolver] that maps a key alias to its
 * [DelegatedKeyMaterial] from the OS key store; the engine, which lives in the
 * shared JVM layer and cannot touch those stores, calls [resolve].
 *
 * When no resolver is installed, or it has no key under that alias,
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
 * implementation from the key type and signs with the provided [PrivateKey]: via
 * JCE for an Android Keystore key, via [PlatformSigningKey.sign] for a desktop
 * TPM key ([withPlatformSigning]). Either way it signs in hardware.
 */
internal class DelegatedKeyProvider(
    private val material: DelegatedKeyMaterial,
) : KeyProvider {
    override fun getPrivate(): PrivateKey = material.privateKey
    override fun getPublic(): PublicKey = material.publicKey
    override fun getType(): KeyType = KeyType.fromKey(material.publicKey)
}

/**
 * A non-exportable EC P-256 key that the platform signs with outside the JCE:
 * the desktop TPM (Windows CNG, Linux TPM 2.0), whose handles no JCE provider
 * knows. sshj forces BouncyCastle for its signatures on desktop, so instead of a
 * JCE provider the engine routes these keys through [withPlatformSigning].
 */
interface PlatformSigningKey : PrivateKey {
    /** ECDSA signature of [data] with SHA-256, DER-encoded as a JCE `Signature` returns it. */
    fun sign(data: ByteArray): ByteArray

    override fun getAlgorithm(): String = "EC"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/**
 * [factories] with every signature able to sign with a [PlatformSigningKey]:
 * other keys go to sshj's own signature untouched.
 */
internal fun withPlatformSigning(factories: List<Factory.Named<KeyAlgorithm>>): List<Factory.Named<KeyAlgorithm>> =
    factories.map { factory ->
        object : Factory.Named<KeyAlgorithm> {
            override fun getName(): String = factory.name
            override fun create(): KeyAlgorithm {
                val inner = factory.create()
                return object : KeyAlgorithm by inner {
                    override fun newSignature(): Signature = PlatformAwareSignature(inner.newSignature())
                }
            }
        }
    }

/** sshj [Signature] that signs with a [PlatformSigningKey] itself and leaves the rest to [inner]. */
private class PlatformAwareSignature(private val inner: Signature) : Signature by inner {
    private var platformKey: PlatformSigningKey? = null
    private val data = ByteArrayOutputStream()

    override fun initSign(key: PrivateKey) {
        platformKey = key as? PlatformSigningKey
        data.reset()
        if (platformKey == null) inner.initSign(key)
    }

    override fun update(H: ByteArray) = update(H, 0, H.size)

    override fun update(H: ByteArray, off: Int, len: Int) {
        if (platformKey != null) data.write(H, off, len) else inner.update(H, off, len)
    }

    override fun sign(): ByteArray = platformKey?.sign(data.toByteArray()) ?: inner.sign()
}

/** The DER `SEQUENCE { INTEGER r, INTEGER s }` of an ECDSA signature given as unsigned big-endian [r] and [s]. */
fun ecdsaSignatureDer(r: ByteArray, s: ByteArray): ByteArray {
    fun derLength(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }
    fun derInteger(v: ByteArray): ByteArray {
        val minimal = BigInteger(1, v).toByteArray()
        return byteArrayOf(0x02) + derLength(minimal.size) + minimal
    }
    val body = derInteger(r) + derInteger(s)
    return byteArrayOf(0x30) + derLength(body.size) + body
}
