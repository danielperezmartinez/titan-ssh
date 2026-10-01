package io.github.danielperezmartinez.titanssh.ssh

import java.util.Base64

actual fun hostKeyFingerprint(publicKeyBase64: String): String {
    // A hand-edited known_hosts line may not be valid base64; show it as unknown.
    val blob = runCatching { Base64.getDecoder().decode(publicKeyBase64) }.getOrNull() ?: return "SHA256:?"
    return sshFingerprintSha256(blob)
}
