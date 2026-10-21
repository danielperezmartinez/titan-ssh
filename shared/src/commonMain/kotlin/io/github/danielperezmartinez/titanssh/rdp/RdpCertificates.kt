package io.github.danielperezmartinez.titanssh.rdp

/**
 * The RDP server certificates the user trusts (ADR-0028 §6), by the SHA-256
 * of the certificate, like the SSH host keys of `known_hosts` (ADR-0005). One
 * per [key] ([certificateKey]): an RDP server is known by the SSH destination
 * that reaches it and its address as seen from there.
 */
interface RdpCertificateStore {
    /** The trusted fingerprint for [key], or null if none is. */
    suspend fun trusted(key: String): String?

    /** Trusts [sha256] for [key], replacing what was trusted before. */
    suspend fun trust(key: String, sha256: String)
}

/** The [RdpCertificateStore] key of an RDP server reached through an SSH destination. */
fun certificateKey(sshHost: String, sshPort: Int, rdpHost: String, rdpPort: Int): String =
    "${sshHost.lowercase()}:$sshPort/${rdpHost.lowercase()}:$rdpPort"

/** `ab:cd:…` for a lowercase hex digest, as the prompt shows it. */
fun formatFingerprint(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

/** The platform's persistent [RdpCertificateStore], next to `known_hosts`. */
expect fun createRdpCertificateStore(): RdpCertificateStore
