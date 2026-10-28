package io.github.danielperezmartinez.titanssh.ssh

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Linux TPM 2.0 backend (ADR-0033) against swtpm, a software TPM speaking
 * the same commands as `/dev/tpmrm0`. Skips without swtpm. The last test also
 * installs the key on a real sshd and logs in signing with the TPM: opt-in with
 * the `-PtitanSshTest*` properties and a password (a throwaway
 * `tools/test-sshd` container).
 */
class LinuxTpmKeysTest {

    private val swtpm = Swtpm.start()
    private val directory = Files.createTempDirectory("titan-tpm-keys").toFile()

    @AfterTest
    fun cleanUp() {
        swtpm?.close()
        directory.deleteRecursively()
        HardwareKeyRegistry.resolver = null
    }

    private fun keys(tpm: Swtpm) = LinuxTpmKeys(directory) { tpm.transport() }

    @Test
    fun creates_a_key_once_and_finds_it_again() {
        val tpm = swtpm ?: return
        val first = keys(tpm).ensureKey("casa")
        val again = keys(tpm).ensureKey("casa")
        val loaded = assertNotNull(LinuxTpmKeys(directory) { tpm.transport() }.load("casa"))

        assertEquals("ecdsa-sha2-nistp256", sshPublicKeyOpenSshLine(first.publicKey).substringBefore(' '))
        assertContentEquals(first.publicKey.encoded, again.publicKey.encoded)
        assertContentEquals(first.publicKey.encoded, loaded.publicKey.encoded)
        assertNull(keys(tpm).load("otra"))
        assertFalse(first.publicKey.encoded.contentEquals(keys(tpm).ensureKey("otra").publicKey.encoded))

        // Only the wrapped blob is on disk, and only its owner can read it.
        val file = directory.listFiles()!!.single { it.name == "casa.tpmkey" }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
    }

    @Test
    fun signs_in_the_tpm_with_the_public_key_it_reports() {
        val tpm = swtpm ?: return
        val key = keys(tpm).ensureKey("firma")
        val data = "session id and userauth request".encodeToByteArray()

        val der = (key.privateKey as PlatformSigningKey).sign(data)

        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(key.publicKey); update(data) }
        assertTrue(verifier.verify(der))
    }

    @Test
    fun aliases_become_safe_file_names() {
        val tpm = swtpm ?: return
        keys(tpm).ensureKey("../trabajo/ñ")
        assertEquals(listOf("..%2Ftrabajo%2F%C3%B1.tpmkey"), directory.list()!!.toList())
        assertNotNull(keys(tpm).load("../trabajo/ñ"))
    }

    @Test
    fun says_why_the_tpm_cannot_be_used() {
        assertTrue(LinuxTpmKeys.unavailableReason(File(directory, "tpmrm0"))!!.contains("TPM 2.0"))

        val locked = File(directory, "tpmrm0").apply { createNewFile() }
        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("---------"))
        assertTrue(LinuxTpmKeys.unavailableReason(locked)!!.contains("grupo tss"))

        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("rw-------"))
        assertNull(LinuxTpmKeys.unavailableReason(locked))
    }

    @Test
    fun reads_auth_failures_from_the_response_code() {
        assertTrue(Tpm2Exception("CreatePrimary", 0x9A2).isAuthFailure) // TPM_RC_BAD_AUTH, session 1
        assertTrue(Tpm2Exception("CreatePrimary", 0x98E).isAuthFailure) // TPM_RC_AUTH_FAIL, session 1
        assertFalse(Tpm2Exception("CreatePrimary", 0x101).isAuthFailure) // TPM_RC_FAILURE
    }

    @Test
    fun installs_the_key_on_a_server_and_logs_in_signing_with_the_tpm() {
        val tpm = swtpm ?: return
        installAndLogIn(keys(tpm), "tpm-e2e")
    }
}
