package im.gar.titanssh.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android hardware-key provisioning: EC P-256 keys generated non-exportable in the
 * Android Keystore / StrongBox (ADR-0005), via [AndroidHardwareKeys].
 */
actual fun isHardwareKeyProvisioningSupported(): Boolean = true

/**
 * Generates or reuses the Keystore key under [alias] off the main thread and
 * returns its `authorized_keys` line. The private material never leaves hardware.
 */
actual suspend fun ensureHardwareKey(alias: String): String =
    withContext(Dispatchers.IO) { AndroidHardwareKeys.ensureKey(alias) }
