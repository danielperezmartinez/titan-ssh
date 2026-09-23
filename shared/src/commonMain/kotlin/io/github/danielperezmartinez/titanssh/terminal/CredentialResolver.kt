package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.config.HostAuth
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.SecretStore
import io.github.danielperezmartinez.titanssh.ssh.SshCredentials

/**
 * Materializes the live [SshCredentials] for a connection from a host's
 * reference-only [HostAuth], fetching the actual secret material from the
 * [SecretStore] at connect time (ADR-0001: secrets live only in the native
 * store, never in the config document). The caller wipes the returned material
 * after the handshake; the engine does not retain it.
 */
class CredentialResolver(private val store: SecretStore) {

    /** Raised when a referenced secret is not present in the store. */
    class MissingSecret(ref: String) :
        Exception("No secret stored under reference '$ref' (create it before connecting)")

    suspend fun resolve(auth: HostAuth): SshCredentials = when (auth) {
        is HostAuth.Password -> {
            val bytes = require(auth.secretRef)
            SshCredentials.Password(bytes.decodeToString().toCharArray())
        }

        is HostAuth.SoftwareKey -> {
            val keyBytes = require(auth.secretRef)
            val passphrase = auth.passphraseRef?.let { ref ->
                store.get(SecretRef(ref))?.decodeToString()?.toCharArray()
            }
            SshCredentials.PrivateKey(keyBytes.decodeToString().toCharArray(), passphrase)
        }

        // The hardware key never materializes as bytes: the engine resolves the
        // alias to a delegated signer in the OS key store (ADR-0005).
        is HostAuth.HardwareKey -> SshCredentials.HardwareKey(auth.alias)
    }

    private suspend fun require(ref: String): ByteArray =
        store.get(SecretRef(ref)) ?: throw MissingSecret(ref)
}
