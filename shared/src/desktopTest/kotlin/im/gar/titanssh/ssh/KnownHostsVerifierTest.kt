package im.gar.titanssh.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class KnownHostsVerifierTest {

    private fun info(base64: String, keyType: String = "ssh-ed25519") = HostKeyInfo(
        host = "host.example",
        port = 22,
        keyType = keyType,
        fingerprintSha256 = "SHA256:irrelevant",
        publicKeyBase64 = base64,
    )

    @Test
    fun first_contact_prompts_accepts_and_remembers() = runTest {
        val store = InMemoryKnownHostsStore()
        var prompts = 0
        val verifier = KnownHostsVerifier(store) { prompts++; true }

        assertTrue(verifier.verify(info("AAAAkey1")), "accepted on first contact")
        assertEquals(1, prompts)

        // Now known: a reject-all prompt must not even be consulted.
        val strict = KnownHostsVerifier(store) { false }
        assertTrue(strict.verify(info("AAAAkey1")), "known matching key accepted silently")
    }

    @Test
    fun declined_first_contact_is_rejected_and_not_stored() = runTest {
        val store = InMemoryKnownHostsStore()
        val verifier = KnownHostsVerifier(store) { false }

        assertFalse(verifier.verify(info("AAAAkey1")))
        assertTrue(store.entriesFor("host.example", 22).isEmpty(), "nothing stored on decline")
    }

    @Test
    fun changed_key_is_rejected_as_mitm_without_prompting() = runTest {
        val store = InMemoryKnownHostsStore(
            listOf(KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey1")),
        )
        var prompts = 0
        val verifier = KnownHostsVerifier(store) { prompts++; true }

        assertFalse(verifier.verify(info("AAAAdifferent")), "different key must be rejected")
        assertEquals(0, prompts, "a changed key must never prompt to overwrite trust")
    }

    @Test
    fun file_store_round_trips_in_openssh_format() = runBlocking {
        val file = File.createTempFile("titan-known-hosts", ".txt").apply { deleteOnExit() }
        val store = FileKnownHostsStore(file)

        store.add(KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey1"))
        store.add(KnownHostEntry("host.example", 2222, "ssh-ed25519", "AAAAkey2"))

        // A fresh instance reads what the first wrote (persistence).
        val reopened = FileKnownHostsStore(file)
        assertEquals(
            listOf("AAAAkey1"),
            reopened.entriesFor("host.example", 22).map { it.publicKeyBase64 },
        )
        assertEquals(
            listOf("AAAAkey2"),
            reopened.entriesFor("host.example", 2222).map { it.publicKeyBase64 },
        )

        // Non-default port uses the [host]:port form on disk.
        assertTrue(file.readText().contains("[host.example]:2222 ssh-ed25519 AAAAkey2"))
    }
}
