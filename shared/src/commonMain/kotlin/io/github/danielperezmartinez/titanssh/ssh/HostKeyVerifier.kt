package io.github.danielperezmartinez.titanssh.ssh

/**
 * The server's presented host key, as the engine sees it during the handshake.
 *
 * @param host the host being connected to.
 * @param port the port being connected to.
 * @param keyType the SSH key type name (e.g. `ssh-ed25519`).
 * @param fingerprintSha256 the standard `SHA256:...` fingerprint (base64, no
 *   padding) of the host key, for display and user confirmation.
 * @param publicKeyBase64 the host key blob in standard base64 (the third field
 *   of an OpenSSH `known_hosts` line), for persistence and exact comparison.
 */
data class HostKeyInfo(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val publicKeyBase64: String,
)

/**
 * Decides whether to trust a server's host key, injected into the engine so the
 * connection primitive stays free of trust policy.
 *
 * The `known_hosts` TOFU policy (accept-on-first-use with user confirmation,
 * ADR-0005) is implemented by the authentication task; the engine only calls
 * this hook and honors its answer. A verifier that returns `false` aborts the
 * connection with [SshHostKeyRejected].
 */
fun interface HostKeyVerifier {
    /** Returns `true` to trust [info] and proceed, `false` to abort. */
    suspend fun verify(info: HostKeyInfo): Boolean
}
