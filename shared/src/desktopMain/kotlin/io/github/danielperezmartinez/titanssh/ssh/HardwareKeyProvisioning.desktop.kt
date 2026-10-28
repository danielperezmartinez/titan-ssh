package io.github.danielperezmartinez.titanssh.ssh

import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.config.desktopConfigDirectory
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop hardware keys (ADR-0033): EC P-256 in the computer's TPM 2.0, through
 * CNG on Windows and `/dev/tpmrm0` on Linux. The private key never leaves the
 * TPM; the engine signs through [PlatformSigningKey].
 */
actual fun hardwareKeyUnavailableReason(): String? = DesktopHardwareKeys.unavailableReason()

actual suspend fun ensureHardwareKey(alias: String): String = withContext(Dispatchers.IO) {
    DesktopHardwareKeys.unavailableReason()?.let { throw HardwareKeyUnsupported(it) }
    sshPublicKeyOpenSshLine(DesktopHardwareKeys.backend.ensureKey(alias).publicKey)
}

/** One platform's TPM key store, by alias. */
internal interface DesktopKeyBackend {
    /** The key under [alias], created in the TPM if absent. */
    fun ensureKey(alias: String): DelegatedKeyMaterial

    /** The key under [alias], or null when there is none. */
    fun load(alias: String): DelegatedKeyMaterial?
}

/** Picks the TPM backend for this OS and hands its keys to the engine. */
object DesktopHardwareKeys {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    internal val backend: DesktopKeyBackend by lazy {
        if (isWindows) {
            WindowsTpmKeys(AppChannel.appName)
        } else {
            LinuxTpmKeys(File(desktopConfigDirectory(), "hardware-keys")) { Tpm2Device() }
        }
    }

    /** Registers the resolver the shared engine uses to sign with a TPM key. Call once on startup. */
    fun install() {
        HardwareKeyRegistry.resolver = { alias -> backend.load(alias) }
    }

    internal fun unavailableReason(): String? =
        if (isWindows) WindowsTpmKeys.unavailableReason() else LinuxTpmKeys.unavailableReason()
}

/** The P-256 public key with affine coordinates [x] and [y] (unsigned big-endian). */
internal fun ecP256PublicKey(x: ByteArray, y: ByteArray): PublicKey {
    val params = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    val point = ECPoint(BigInteger(1, x), BigInteger(1, y))
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
}
