package im.gar.titanssh.ssh

import java.security.Security
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Android crypto setup for the SSH engine (ADR-0004 / ADR-0005).
 *
 * Two things are needed for hardware-backed signing to work on Android:
 *
 * 1. **Full BouncyCastle in the JCE list as "BC".** Android bundles a
 *    stripped-down "BC" provider missing algorithms sshj needs to negotiate with
 *    a modern OpenSSH server (curve25519 key exchange, chacha20-poly1305, …).
 *    Replacing it makes `SecurityUtils.isBouncyCastleRegistered()` true, so sshj
 *    offers those strong algorithms.
 *
 * 2. **Do not let sshj force "BC" as the provider name.** sshj obtains its JCE
 *    `Signature` via `SecurityUtils.getSignature(algo)`, which forces the named
 *    provider when one is set. Forcing BouncyCastle breaks signing with a
 *    non-exportable Android Keystore key: BC needs the key material and throws
 *    "no encoding for EC private key". With no forced provider,
 *    `Signature.getInstance(algo)` defers provider selection to `initSign`, and
 *    the framework routes an AndroidKeyStore key to the AndroidKeyStore provider
 *    (the documented way to sign with a hardware key). `setRegisterBouncyCastle(false)`
 *    keeps sshj's provider name unset while BC stays available in the JCE list.
 *
 * Call [install] once on startup, before opening any connection. Android-only:
 * on desktop the JDK providers already handle everything.
 */
object SshAndroidCrypto {
    fun install() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        // Keep BC available (above) but unforced, so hardware-key signing works.
        SecurityUtils.setRegisterBouncyCastle(false)
    }
}
