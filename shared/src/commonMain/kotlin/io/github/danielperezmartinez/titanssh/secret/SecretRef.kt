package io.github.danielperezmartinez.titanssh.secret

/**
 * Stable, opaque handle for one secret held by a [SecretStore].
 *
 * The value is used as the key/alias in the native secret store of each platform
 * (Android KeyStore alias, Windows Credential target, Linux Secret Service
 * attribute), so it is restricted to a portable character set and a conservative
 * length that every backend accepts.
 *
 * A [SecretRef] is only a name: it never carries secret material.
 */
@JvmInline
value class SecretRef(val value: String) {
    init {
        require(value.isNotEmpty()) { "SecretRef must not be empty" }
        require(value.length <= MAX_LENGTH) {
            "SecretRef must be at most $MAX_LENGTH characters, was ${value.length}"
        }
        require(value.all { it in ALLOWED }) {
            "SecretRef may only contain [A-Za-z0-9._-], was '$value'"
        }
    }

    companion object {
        const val MAX_LENGTH: Int = 200

        private val ALLOWED: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('.', '_', '-')).toSet()
    }
}
