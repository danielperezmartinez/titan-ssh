package io.github.danielperezmartinez.titanssh.ssh

import java.security.SecureRandom
import java.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil

actual fun generateEd25519KeyPair(comment: String): GeneratedKeyPair {
    val generator = Ed25519KeyPairGenerator()
    generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val pair = generator.generateKeyPair()
    // BouncyCastle writes ed25519 in the openssh-key-v1 format, the one
    // ssh-keygen writes and sshj reads.
    val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
    val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(privateBlob)
    privateBlob.fill(0)
    val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
    val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
    val line = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicBlob)}" +
        comment.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
    return GeneratedKeyPair(pem.toCharArray(), line)
}

actual fun publicLineOfPrivateKey(privateKeyPem: CharArray, passphrase: CharArray?): String {
    val keys = try {
        SSHClient(sshjConfig(keepAlive = false)).loadKeys(
            String(privateKeyPem),
            null,
            passphrase?.let { PasswordUtils.createOneOff(it.copyOf()) },
        )
    } catch (e: Exception) {
        throw IllegalArgumentException("The private key could not be read: ${e.message}", e)
    }
    return try {
        sshPublicKeyOpenSshLine(keys.public)
    } catch (e: Exception) {
        throw IllegalArgumentException("The private key could not be read: ${e.message}", e)
    }
}

/** Line length of the base64 body, as ssh-keygen writes it. */
private const val PEM_LINE = 70
