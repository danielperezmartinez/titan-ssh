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
 * - **Desktop**: the same with an EC P-256 key in the computer's TPM 2.0
 *   (ADR-0033): the Microsoft Platform Crypto Provider on Windows, `/dev/tpmrm0`
 *   on Linux. Without a usable TPM, [hardwareKeyUnavailableReason] says why and
 *   the editor disables the action with it.
 *
 * This mirrors the alias-based model of [SshCredentials.HardwareKey]: the config
 * document only ever stores the alias, never key material (ADR-0001).
 */
expect fun hardwareKeyUnavailableReason(): String?

/**
 * Ensures a non-exportable hardware key exists under [alias] in the OS key store,
 * creating it if absent, and returns its OpenSSH `authorized_keys` line to enroll
 * on the server (ADR-0005).
 *
 * @throws HardwareKeyUnsupported when this computer has no usable key store
 *   ([hardwareKeyUnavailableReason]).
 */
expect suspend fun ensureHardwareKey(alias: String): String

/** Raised when hardware-key provisioning is requested where it cannot work, with the reason. */
class HardwareKeyUnsupported(
    message: String = "Hardware key provisioning is not available on this platform",
) : Exception(message)
