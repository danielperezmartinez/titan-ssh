package io.github.danielperezmartinez.titanssh.ssh

/**
 * A software key pair made in the app (ADR-0030): [privateKeyPem] is the
 * unencrypted OpenSSH private key, for the SecretStore only (ADR-0001), and
 * [publicLine] its `authorized_keys` line. The caller wipes [privateKeyPem]
 * once it is stored.
 */
class GeneratedKeyPair(val privateKeyPem: CharArray, val publicLine: String)

/**
 * Generates an ed25519 key pair with [comment] at the end of its public line.
 * Same implementation on Android and desktop (BouncyCastle, `jvmShared`).
 */
expect fun generateEd25519KeyPair(comment: String): GeneratedKeyPair

/**
 * The `authorized_keys` line (`<type> <base64>`, no comment) of a private key
 * in OpenSSH/PEM text form, decrypted with [passphrase] when it has one.
 *
 * @throws IllegalArgumentException if the text is not a key this app can read
 *   or the passphrase is wrong.
 */
expect fun publicLineOfPrivateKey(privateKeyPem: CharArray, passphrase: CharArray?): String

/**
 * `<type> <base64>` of an `authorized_keys` line, without options or comment:
 * what tells two lines for the same key apart from lines for other keys.
 * `null` if [line] has no key in it.
 */
fun authorizedKeyIdentity(line: String): String? {
    val fields = line.trim().split(Regex("\\s+"))
    val typeAt = fields.indexOfFirst { KEY_TYPE.matches(it) }
    if (typeAt < 0 || typeAt + 1 >= fields.size) return null
    return "${fields[typeAt]} ${fields[typeAt + 1]}"
}

private val KEY_TYPE = Regex("(ssh-(ed25519|rsa|dss)|ecdsa-sha2-nistp(256|384|521)|sk-[a-z0-9@.-]+)(-cert-v01@openssh\\.com)?")
