package io.github.danielperezmartinez.titanssh.ssh

import java.security.Provider
import java.security.Security

/**
 * A JCE provider with a single service: the `Ed25519` key factory, taken from
 * BouncyCastle.
 *
 * sshj builds every Ed25519 key, the server's host key included, with
 * `KeyFactory.getInstance("Ed25519")` and later recognises it by
 * `Key.getAlgorithm()` being `Ed25519` or `EdDSA`. From Android 16 the
 * platform's Conscrypt answers that lookup first, and its keys report the OID
 * `1.3.101.112` instead: sshj cannot tell their type, and every connection to
 * a host with an ed25519 key fails with "Don't know how to encode key".
 * [install] puts this provider ahead of the platform ones so that one lookup
 * gets BouncyCastle's keys, and nothing else changes (forcing BouncyCastle for
 * everything breaks hardware-key signing, see `SshAndroidCrypto`).
 */
internal class Ed25519KeysProvider : Provider(NAME, 1.0, "Ed25519 keys from BouncyCastle, for sshj") {
    init {
        put("KeyFactory.Ed25519", "org.bouncycastle.jcajce.provider.asymmetric.edec.KeyFactorySpi\$Ed25519")
    }

    companion object {
        const val NAME = "TitanEd25519"

        /** Puts the provider first in the JCE list. Idempotent. */
        fun install() {
            if (Security.getProvider(NAME) == null) Security.insertProviderAt(Ed25519KeysProvider(), 1)
        }
    }
}
