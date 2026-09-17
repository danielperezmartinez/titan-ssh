package im.gar.titanssh.ssh

/**
 * Credential material handed to the engine for one connection attempt. It is
 * sourced from the `SecretStore` (ADR-0001) right before connecting and should
 * be wiped by the caller afterwards; the engine does not retain it.
 *
 * The hardware-backed non-exportable key is a separate variant added by the
 * signer task ([[Autenticación SSH signer en hardware y verificación de host]]),
 * because its private key never materializes as bytes here.
 */
sealed interface SshCredentials {

    /** Password authentication (fallback, ADR-0005). */
    class Password(val password: CharArray) : SshCredentials

    /**
     * Software-held private key (fallback, ADR-0005): the key bytes are loaded
     * into memory to sign. Preferred key type is ed25519.
     *
     * @param privateKeyPem the private key in OpenSSH/PEM text form.
     * @param passphrase passphrase protecting the key, or `null` if unencrypted.
     */
    class PrivateKey(
        val privateKeyPem: CharArray,
        val passphrase: CharArray? = null,
    ) : SshCredentials
}
