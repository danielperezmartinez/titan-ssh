package io.github.danielperezmartinez.titanssh.ssh

import io.github.danielperezmartinez.titanssh.config.PrivateFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Linux TPM 2.0 SSH keys (ADR-0033), spoken to directly through the kernel's
 * resource manager, with no tpm2-tss or PKCS#11 package. Each key is an EC
 * P-256 signing key the TPM creates and wraps; the wrapped blob lives in
 * [directory] (owner-only) and only this computer's TPM can use it.
 *
 * [transport] opens a fresh connection per operation; closing it flushes
 * anything left loaded.
 */
internal class LinuxTpmKeys(
    private val directory: File,
    private val transport: () -> Tpm2Transport,
) : DesktopKeyBackend {

    override fun ensureKey(alias: String): DelegatedKeyMaterial =
        load(alias) ?: create(alias)

    override fun load(alias: String): DelegatedKeyMaterial? {
        val file = fileOf(alias)
        if (!file.isFile) return null
        val blob = Tpm2KeyBlob.decode(file.readBytes())
        val (x, y) = blob.eccPoint()
        return DelegatedKeyMaterial(ecP256PublicKey(x, y), TpmSigningKey(blob))
    }

    private fun create(alias: String): DelegatedKeyMaterial {
        val blob = withTpm { tpm ->
            val (parent, flush) = tpm.storageKey()
            try {
                tpm.createSigningKey(parent)
            } finally {
                if (flush) runCatching { tpm.flush(parent) }
            }
        }
        PrivateFiles.secureDirectory(directory)
        // Written aside and moved, so a half-written file never passes for a key.
        val tmp = File(directory, "${fileOf(alias).name}.tmp")
        PrivateFiles.writeBytes(tmp, blob.encode())
        Files.move(tmp.toPath(), fileOf(alias).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return checkNotNull(load(alias))
    }

    private inner class TpmSigningKey(private val blob: Tpm2KeyBlob) : PlatformSigningKey {
        override fun sign(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val (r, s) = withTpm { tpm ->
                val (parent, flushParent) = tpm.storageKey()
                try {
                    val key = tpm.load(parent, blob)
                    try {
                        tpm.sign(key, digest)
                    } finally {
                        runCatching { tpm.flush(key) }
                    }
                } finally {
                    if (flushParent) runCatching { tpm.flush(parent) }
                }
            }
            return ecdsaSignatureDer(r, s)
        }
    }

    private fun <T> withTpm(block: (Tpm2) -> T): T = transport().use { block(Tpm2(it)) }

    /** The alias as a file name: letters, digits, `.`, `-` and `_` kept, anything else `%XX`. */
    private fun fileOf(alias: String): File {
        val safe = alias.encodeToByteArray().joinToString("") { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-") c.toString() else "%%%02X".format(c.code)
        }
        return File(directory, "$safe.tpmkey")
    }

    companion object {
        /** Why this computer cannot hold a TPM key for the user, or null when it can. */
        fun unavailableReason(device: File = File(Tpm2Device.DEFAULT_PATH)): String? = when {
            !device.exists() ->
                "Este equipo no tiene un TPM 2.0 activo (puede estar desactivado en la UEFI/BIOS)."
            !Files.isReadable(device.toPath()) || !Files.isWritable(device.toPath()) ->
                "Tu usuario no tiene acceso al TPM (${device.path}). Añádelo al grupo tss " +
                    "(sudo usermod -aG tss \$USER) y vuelve a iniciar sesión."
            else -> null
        }
    }
}
