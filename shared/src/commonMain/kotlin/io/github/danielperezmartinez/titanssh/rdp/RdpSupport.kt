package io.github.danielperezmartinez.titanssh.rdp

/** What an RDP tab needs from the app (ADR-0028). */
class RdpSupport(
    /** Loads the native engine, the first time an RDP tab connects. */
    load: () -> RdpAvailability,
    val certificates: RdpCertificateStore,
    /** The secret under a SecretStore reference, or null if there is none. */
    val secret: suspend (String) -> ByteArray?,
    /** How this device introduces itself to the server (shown in its session list). */
    val clientName: String = "titan-ssh",
) {
    /** The native engine, or why there is none. */
    val availability: RdpAvailability by lazy(load)
}
