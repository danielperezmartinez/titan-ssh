package io.github.danielperezmartinez.titanssh.ssh

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.schmizz.sshj.common.Buffer

/**
 * The engine's path for desktop TPM keys (ADR-0033): sshj signs with a
 * [PlatformSigningKey] through [withPlatformSigning], and the result is a
 * signature any OpenSSH server verifies. A software key stands in for the TPM.
 */
class PlatformSigningTest {

    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    /** Signs like the TPM backends: raw r || s from the "hardware", turned into DER. */
    private inner class FakeTpmKey : PlatformSigningKey {
        var calls = 0
        override fun sign(data: ByteArray): ByteArray {
            calls++
            val raw = Signature.getInstance("SHA256withECDSAinP1363Format")
                .apply { initSign(keyPair.private); update(data) }
                .sign()
            return ecdsaSignatureDer(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
        }
    }

    private fun ecdsaAlgorithm() =
        sshjConfig(keepAlive = false).keyAlgorithms.single { it.name == "ecdsa-sha2-nistp256" }.create()

    /** Signs [data] with [key] through the engine's sshj config and checks the server-side verification. */
    private fun signsAndVerifies(key: PrivateKey, data: ByteArray): Boolean {
        val algorithm = ecdsaAlgorithm()
        val signer = algorithm.newSignature().apply { initSign(key); update(data, 0, 4); update(data, 4, data.size - 4) }
        val blob = Buffer.PlainBuffer().putString(algorithm.keyAlgorithm).putBytes(signer.encode(signer.sign())).compactData
        return algorithm.newSignature().apply { initVerify(keyPair.public); update(data) }.verify(blob)
    }

    @Test
    fun a_platform_key_signs_through_the_engine_config() {
        val key = FakeTpmKey()
        assertTrue(signsAndVerifies(key, "session id and userauth request".encodeToByteArray()))
        assertEquals(1, key.calls)
    }

    @Test
    fun other_keys_still_sign_through_sshj() {
        assertTrue(signsAndVerifies(keyPair.private, "session id and userauth request".encodeToByteArray()))
    }

    @Test
    fun der_from_r_and_s_matches_what_the_jce_verifies() {
        // Enough signatures that some r or s has its top bit set and needs the leading zero.
        repeat(64) { i ->
            val data = "message $i".encodeToByteArray()
            val der = FakeTpmKey().sign(data)
            val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(keyPair.public); update(data) }.verify(der)
            assertTrue(ok, "signature $i")
        }
    }
}
