package io.github.danielperezmartinez.titanssh.ssh

/**
 * Desktop (Windows/Linux) has no portable non-exportable key store in v1, so
 * hardware-key provisioning is unavailable and the host editor disables it. A
 * software key ([SshCredentials.PrivateKey]) is the desktop fallback (ADR-0005).
 */
actual fun isHardwareKeyProvisioningSupported(): Boolean = false

actual suspend fun ensureHardwareKey(alias: String): String = throw HardwareKeyUnsupported()
