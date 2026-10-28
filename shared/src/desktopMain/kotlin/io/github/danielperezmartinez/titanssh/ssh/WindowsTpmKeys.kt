package io.github.danielperezmartinez.titanssh.ssh

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Windows TPM 2.0 SSH keys (ADR-0033) through CNG's Microsoft Platform Crypto
 * Provider: Windows creates each EC P-256 key in the TPM, keeps it for the user
 * and never lets it be exported. Keys are named `<app>/<alias>`, so the stable
 * app and the canary keep their own, as on Android.
 */
internal class WindowsTpmKeys(private val namespace: String) : DesktopKeyBackend {

    override fun ensureKey(alias: String): DelegatedKeyMaterial =
        load(alias) ?: withProvider { provider ->
            val key = LongByReference()
            check("NCryptCreatePersistedKey", ncrypt.NCryptCreatePersistedKey(provider, key, WString("ECDSA_P256"), keyName(alias), 0, 0))
            try {
                check("NCryptFinalizeKey", ncrypt.NCryptFinalizeKey(key.value, NCRYPT_SILENT_FLAG))
            } finally {
                ncrypt.NCryptFreeObject(key.value)
            }
            checkNotNull(load(alias))
        }

    override fun load(alias: String): DelegatedKeyMaterial? = withKey(alias) { key ->
        DelegatedKeyMaterial(publicKey(key), CngSigningKey(alias))
    }

    /** The key's public point, from its `ECCPUBLICBLOB` (BCRYPT_ECCKEY_BLOB header, then X and Y). */
    private fun publicKey(key: Long): java.security.PublicKey {
        val size = IntByReference()
        check("NCryptExportKey", ncrypt.NCryptExportKey(key, 0, WString("ECCPUBLICBLOB"), null, null, 0, size, 0))
        val blob = ByteArray(size.value)
        check("NCryptExportKey", ncrypt.NCryptExportKey(key, 0, WString("ECCPUBLICBLOB"), null, blob, blob.size, size, 0))
        val header = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        check(header.int == BCRYPT_ECDSA_PUBLIC_P256_MAGIC) { "Not an ECDSA P-256 key" }
        val coordinate = header.int
        return ecP256PublicKey(blob.copyOfRange(8, 8 + coordinate), blob.copyOfRange(8 + coordinate, 8 + 2 * coordinate))
    }

    private inner class CngSigningKey(private val alias: String) : PlatformSigningKey {
        override fun sign(data: ByteArray): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256").digest(data)
            val signature = withKey(alias) { key ->
                val size = IntByReference()
                val out = ByteArray(64)
                check("NCryptSignHash", ncrypt.NCryptSignHash(key, null, hash, hash.size, out, out.size, size, NCRYPT_SILENT_FLAG))
                out.copyOf(size.value)
            } ?: throw IllegalStateException("The TPM key '$alias' is gone")
            // CNG gives r || s, each as long as the curve's coordinates.
            val half = signature.size / 2
            return ecdsaSignatureDer(signature.copyOfRange(0, half), signature.copyOfRange(half, signature.size))
        }
    }

    /** Runs [block] with the key open, or returns null when there is no key under [alias]. */
    private fun <T> withKey(alias: String, block: (Long) -> T): T? = withProvider { provider ->
        val key = LongByReference()
        val status = ncrypt.NCryptOpenKey(provider, key, keyName(alias), 0, NCRYPT_SILENT_FLAG)
        if (status == NTE_BAD_KEYSET) return@withProvider null
        check("NCryptOpenKey", status)
        try {
            block(key.value)
        } finally {
            ncrypt.NCryptFreeObject(key.value)
        }
    }

    private fun <T> withProvider(block: (Long) -> T): T {
        val provider = LongByReference()
        check("NCryptOpenStorageProvider", ncrypt.NCryptOpenStorageProvider(provider, WString(PLATFORM_PROVIDER), 0))
        try {
            return block(provider.value)
        } finally {
            ncrypt.NCryptFreeObject(provider.value)
        }
    }

    private fun keyName(alias: String) = WString("$namespace/$alias")

    @Suppress("FunctionName")
    private interface NCrypt : Library {
        fun NCryptOpenStorageProvider(phProvider: LongByReference, pszProviderName: WString, dwFlags: Int): Int
        fun NCryptOpenKey(hProvider: Long, phKey: LongByReference, pszKeyName: WString, dwLegacyKeySpec: Int, dwFlags: Int): Int
        fun NCryptCreatePersistedKey(
            hProvider: Long, phKey: LongByReference, pszAlgId: WString, pszKeyName: WString, dwLegacyKeySpec: Int, dwFlags: Int,
        ): Int
        fun NCryptFinalizeKey(hKey: Long, dwFlags: Int): Int
        fun NCryptExportKey(
            hKey: Long, hExportKey: Long, pszBlobType: WString, pParameterList: Pointer?,
            pbOutput: ByteArray?, cbOutput: Int, pcbResult: IntByReference, dwFlags: Int,
        ): Int
        fun NCryptSignHash(
            hKey: Long, pPaddingInfo: Pointer?, pbHashValue: ByteArray, cbHashValue: Int,
            pbSignature: ByteArray, cbSignature: Int, pcbResult: IntByReference, dwFlags: Int,
        ): Int
        fun NCryptFreeObject(hObject: Long): Int
    }

    /** A CNG call that failed, with its `SECURITY_STATUS`. */
    class CngException(call: String, val status: Int) :
        Exception("$call failed: 0x${Integer.toHexString(status)}")

    companion object {
        private const val PLATFORM_PROVIDER = "Microsoft Platform Crypto Provider"
        private const val NCRYPT_SILENT_FLAG = 0x40
        private const val NTE_BAD_KEYSET = 0x80090016.toInt()
        private const val BCRYPT_ECDSA_PUBLIC_P256_MAGIC = 0x31534345 // "ECS1"

        private val ncrypt: NCrypt by lazy { Native.load("ncrypt", NCrypt::class.java) }

        private fun check(call: String, status: Int) {
            if (status != 0) throw CngException(call, status)
        }

        /**
         * Why this computer cannot hold a TPM key for the user, or null when it
         * can. The provider only opens with a TPM 2.0 that Windows can use.
         */
        fun unavailableReason(): String? = runCatching {
            val provider = LongByReference()
            val status = ncrypt.NCryptOpenStorageProvider(provider, WString(PLATFORM_PROVIDER), 0)
            if (status == 0) ncrypt.NCryptFreeObject(provider.value)
            status
        }.fold(
            onSuccess = { status ->
                if (status == 0) null
                else "Windows no encuentra un TPM 2.0 que pueda usar (código 0x${Integer.toHexString(status)}). " +
                    "Comprueba en tpm.msc que está activo."
            },
            onFailure = { "No se pudo abrir CNG (${it.message})." },
        )
    }
}
