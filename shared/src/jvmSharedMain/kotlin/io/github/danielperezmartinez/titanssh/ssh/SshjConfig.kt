package io.github.danielperezmartinez.titanssh.ssh

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory

/**
 * The algorithms the engine offers, most preferred first: close to the current
 * OpenSSH client defaults, limited to what sshj implements. Group exchange is
 * left out because sshj accepts moduli below 2048 bits in it.
 */
internal object SshAlgorithms {
    val keyExchange = listOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
        "ecdh-sha2-nistp256",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp521",
        "diffie-hellman-group16-sha512",
        "diffie-hellman-group18-sha512",
        // Not an algorithm: asks the server for server-sig-algs (RFC 8308).
        "ext-info-c",
    )

    /** Host key algorithms, and the signatures used for public key authentication. */
    val keys = listOf(
        "ssh-ed25519-cert-v01@openssh.com",
        "ssh-ed25519",
        "sk-ssh-ed25519@openssh.com",
        "sk-ecdsa-sha2-nistp256@openssh.com",
        "ecdsa-sha2-nistp521-cert-v01@openssh.com",
        "ecdsa-sha2-nistp521",
        "ecdsa-sha2-nistp384-cert-v01@openssh.com",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp256-cert-v01@openssh.com",
        "ecdsa-sha2-nistp256",
        "rsa-sha2-512",
        "rsa-sha2-256",
    )

    val ciphers = listOf(
        "chacha20-poly1305@openssh.com",
        "aes128-gcm@openssh.com",
        "aes256-gcm@openssh.com",
        "aes128-ctr",
        "aes192-ctr",
        "aes256-ctr",
    )

    val macs = listOf(
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512-etm@openssh.com",
    )
}

/** sshj configuration for one connection: [SshAlgorithms] and, if asked, keepalive. */
internal fun sshjConfig(keepAlive: Boolean): Config = DefaultConfig().apply {
    // Picked from sshj's own lists, which already leave out the ciphers this
    // JCE cannot initialise.
    keyExchangeFactories = keyExchangeFactories.only(SshAlgorithms.keyExchange)
    keyAlgorithms = keyAlgorithms.only(SshAlgorithms.keys)
    cipherFactories = cipherFactories.only(SshAlgorithms.ciphers)
    macFactories = macFactories.only(SshAlgorithms.macs)
    if (keepAlive) keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
}

/** The factories named in [names], in that order. */
private fun <T> List<Factory.Named<T>>.only(names: List<String>): List<Factory.Named<T>> {
    val byName = associateBy { it.name }
    return names.mapNotNull { byName[it] }
}

/**
 * The kind of algorithm the server shared none of with us, read from the
 * failure sshj raises then (`"Unable to reach a settlement of <list>: …"`), or
 * null when [t] is a different failure.
 */
internal fun unnegotiatedAlgorithm(t: Throwable): SshAlgorithmKind? {
    var cur: Throwable? = t
    while (cur != null) {
        val list = cur.message?.let { NO_SETTLEMENT.find(it) }?.groupValues?.get(1)
        when {
            list == null -> {}
            list == "KeyExchangeAlgorithms" -> return SshAlgorithmKind.KEY_EXCHANGE
            list == "HostKeyAlgorithms" -> return SshAlgorithmKind.HOST_KEY
            list.endsWith("CipherAlgorithms") -> return SshAlgorithmKind.CIPHER
            list.endsWith("MACAlgorithms") -> return SshAlgorithmKind.MAC
            list.endsWith("CompressionAlgorithms") -> return SshAlgorithmKind.COMPRESSION
        }
        cur = cur.cause
    }
    return null
}

private val NO_SETTLEMENT = Regex("""Unable to reach a settlement of (\w+)""")
