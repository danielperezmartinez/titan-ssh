package io.github.danielperezmartinez.titanssh.secret

/**
 * Writes authentication material into the [SecretStore] so the reference-only
 * [HostAuth][io.github.danielperezmartinez.titanssh.config.HostAuth] of a host resolves to live
 * credentials at connect time (ADR-0001). It is the write-side counterpart of the
 * read-side [CredentialResolver][io.github.danielperezmartinez.titanssh.terminal.CredentialResolver]:
 * the config document keeps only references, and the plaintext never lingers.
 *
 * Every entry point takes ownership of the plaintext [CharArray] it is given and
 * [wipe]s the transient byte copy it derives before returning; the caller should
 * likewise clear its own buffer once done.
 *
 * Passwords, key passphrases and software-fallback private keys belong here. A
 * hardware-backed, non-exportable key does **not**: it is provisioned in the OS
 * key store by
 * [ensureHardwareKey][io.github.danielperezmartinez.titanssh.ssh.ensureHardwareKey] and referenced only
 * by alias (ADR-0005).
 */
class SecretProvisioner(private val store: SecretStore) {

    /** Raised when the material handed in is empty or malformed. */
    class InvalidMaterial(message: String) : Exception(message)

    /**
     * Stores a connection [password] under [ref], overwriting any previous value.
     */
    suspend fun savePassword(ref: SecretRef, password: CharArray) {
        if (password.isEmpty()) throw InvalidMaterial("The password is empty")
        putChars(ref, password)
    }

    /**
     * Imports an existing private key in OpenSSH/PEM text form under [keyRef], and,
     * when a [passphrase] is supplied, stores it under [passphraseRef].
     *
     * The public key is not derived here: an imported key already has its public
     * counterpart, which the user enrolls on the server themselves.
     *
     * @throws InvalidMaterial if [pem] is blank or does not look like a PEM private
     *   key, or if a [passphrase] is given without a [passphraseRef] to hold it.
     */
    suspend fun importPrivateKey(
        keyRef: SecretRef,
        pem: CharArray,
        passphraseRef: SecretRef? = null,
        passphrase: CharArray? = null,
    ) {
        if (pem.isEmpty()) throw InvalidMaterial("The private key is empty")
        if (!looksLikePrivateKeyPem(pem)) {
            throw InvalidMaterial("The text does not look like a PEM private key (expected a '-----BEGIN ... PRIVATE KEY-----' block)")
        }
        if (passphrase != null && passphrase.isNotEmpty() && passphraseRef == null) {
            throw InvalidMaterial("A passphrase was given but no reference to store it under")
        }
        putChars(keyRef, pem)
        if (passphraseRef != null && passphrase != null && passphrase.isNotEmpty()) {
            putChars(passphraseRef, passphrase)
        }
    }

    /** Whether a secret is already stored under [ref]. */
    suspend fun has(ref: SecretRef): Boolean = store.contains(ref)

    /** Removes the secret stored under [ref], if any. */
    suspend fun remove(ref: SecretRef) = store.remove(ref)

    /** Encodes [chars] to UTF-8, stores it, and wipes the transient byte copy. */
    private suspend fun putChars(ref: SecretRef, chars: CharArray) {
        val bytes = chars.concatToString().encodeToByteArray()
        try {
            store.put(ref, bytes)
        } finally {
            bytes.wipe()
        }
    }

    private fun looksLikePrivateKeyPem(pem: CharArray): Boolean {
        val text = pem.concatToString()
        return text.contains("-----BEGIN") && text.contains("PRIVATE KEY-----")
    }
}
