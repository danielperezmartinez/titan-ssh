package io.github.danielperezmartinez.titanssh.secret

/**
 * SSH authentication domain model. Encodes the policy fixed by ADR-0005:
 * prefer public-key over password, prefer ed25519 among key types, and prefer a
 * hardware-backed non-exportable key over a software one.
 *
 * These types describe *which* method authenticates a connection and *where*
 * its secret lives; the actual signing/handshake is wired to the SSH library in
 * a follow-up task. Any secret material referenced here is custodied by a
 * [SecretStore] (ADR-0001), never inlined.
 */

/** SSH key algorithm, ordered by project preference (lower [rank] = preferred). */
enum class SshKeyType(val rank: Int, val sshName: String) {
    ED25519(0, "ssh-ed25519"),
    ECDSA_P256(1, "ecdsa-sha2-nistp256"),
    RSA_3072_PLUS(2, "rsa-sha2-512"),
}

/** Where a private key lives, ordered by preference (lower [rank] = preferred). */
enum class KeyStorage(val rank: Int) {
    /**
     * Generated in and non-exportable from the OS key store (Android Keystore /
     * StrongBox and the desktop equivalent). Signs without the key ever leaving
     * the hardware. The v1 goal (ADR-0005).
     */
    HARDWARE_NON_EXPORTABLE(0),

    /**
     * Private key bytes custodied (encrypted) by the [SecretStore] and loaded
     * into memory to sign. Fallback where hardware-backed keys are unavailable.
     */
    SOFTWARE_FALLBACK(1),
}

/** How a connection authenticates. */
sealed interface AuthMethod {
    /**
     * Public-key authentication (preferred, ADR-0005).
     *
     * @param keyType algorithm of the key.
     * @param storage where the private key lives.
     * @param secretRef reference to the custodied key material for
     *   [KeyStorage.SOFTWARE_FALLBACK]; `null` for
     *   [KeyStorage.HARDWARE_NON_EXPORTABLE], whose key never leaves the store
     *   and is addressed by its OS-key-store alias instead.
     */
    data class PublicKey(
        val keyType: SshKeyType,
        val storage: KeyStorage,
        val secretRef: SecretRef?,
    ) : AuthMethod {
        init {
            when (storage) {
                KeyStorage.SOFTWARE_FALLBACK ->
                    require(secretRef != null) {
                        "A software-fallback key must reference its custodied material"
                    }
                KeyStorage.HARDWARE_NON_EXPORTABLE ->
                    require(secretRef == null) {
                        "A hardware non-exportable key is not custodied by SecretStore"
                    }
            }
        }
    }

    /**
     * Password authentication (fallback, ADR-0005). The password is custodied by
     * the [SecretStore] and may be gated behind biometrics on platforms that
     * support it.
     *
     * @param secretRef reference to the custodied password.
     */
    data class Password(val secretRef: SecretRef) : AuthMethod
}

/**
 * Orders candidate auth methods by project preference (most preferred first):
 * public-key before password; within keys, by [SshKeyType.rank] then
 * [KeyStorage.rank].
 *
 * Use this to pick which method to attempt first when a host offers several.
 */
fun List<AuthMethod>.byPreference(): List<AuthMethod> =
    sortedWith(
        compareBy(
            { if (it is AuthMethod.PublicKey) 0 else 1 },
            { (it as? AuthMethod.PublicKey)?.keyType?.rank ?: Int.MAX_VALUE },
            { (it as? AuthMethod.PublicKey)?.storage?.rank ?: Int.MAX_VALUE },
        ),
    )
