package io.github.danielperezmartinez.titanssh.ssh

/**
 * Provisioning seam for hardware-backed, non-exportable SSH keys (ADR-0005),
 * callable from `commonMain` UI (the host editor) so it does not need to touch a
 * platform key store directly.
 *
 * - **Android**: [ensureHardwareKey] generates (or reuses) an EC P-256 key in the
 *   Android Keystore / StrongBox under the given alias and returns its OpenSSH
 *   `authorized_keys` line; the private key never leaves the hardware. Backed by
 *   [AndroidHardwareKeys].
 * - **Desktop (v1)**: there is no portable non-exportable key store, so
 *   [isHardwareKeyProvisioningSupported] is `false` and [ensureHardwareKey] throws
 *   [HardwareKeyUnsupported]. The editor disables the action with a note.
 *
 * This mirrors the alias-based model of [SshCredentials.HardwareKey]: the config
 * document only ever stores the alias, never key material (ADR-0001).
 */
expect fun isHardwareKeyProvisioningSupported(): Boolean

/**
 * Ensures a non-exportable hardware key exists under [alias] in the OS key store,
 * creating it if absent, and returns its OpenSSH `authorized_keys` line to enroll
 * on the server (ADR-0005).
 *
 * @throws HardwareKeyUnsupported on platforms without a non-exportable key store
 *   (desktop v1).
 */
expect suspend fun ensureHardwareKey(alias: String): String

/** Raised when hardware-key provisioning is requested on an unsupported platform. */
class HardwareKeyUnsupported(
    message: String = "Hardware key provisioning is not available on this platform",
) : Exception(message)
