package io.github.danielperezmartinez.titanssh.ssh

import java.security.Signature
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows TPM backend (ADR-0033) against the computer's real TPM 2.0
 * through CNG. Runs only on Windows; each run makes new keys, named after the
 * time, under a test namespace apart from the app's.
 */
class WindowsTpmKeysTest {

    private val onWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val keys = WindowsTpmKeys("titan-ssh-test")
    private val run = System.currentTimeMillis().toString(36)

    @AfterTest
    fun clearResolver() {
        HardwareKeyRegistry.resolver = null
    }

    @Test
    fun creates_a_key_once_and_signs_with_it_in_the_tpm() {
        if (!onWindows) return println("[tpm] skipped: not Windows")
        assertNull(WindowsTpmKeys.unavailableReason())
        val alias = "keys-$run"
        assertNull(keys.load(alias))

        val first = keys.ensureKey(alias)
        val again = keys.ensureKey(alias)
        assertContentEquals(first.publicKey.encoded, again.publicKey.encoded)
        assertContentEquals(first.publicKey.encoded, assertNotNull(keys.load(alias)).publicKey.encoded)
        assertEquals("ecdsa-sha2-nistp256", sshPublicKeyOpenSshLine(first.publicKey).substringBefore(' '))

        val data = "session id and userauth request".encodeToByteArray()
        val der = (first.privateKey as PlatformSigningKey).sign(data)
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(first.publicKey); update(data) }.verify(der))
    }

    @Test
    fun installs_the_key_on_a_server_and_logs_in_signing_with_the_tpm() {
        if (!onWindows) return println("[tpm] skipped: not Windows")
        installAndLogIn(keys, "e2e-$run")
    }
}
