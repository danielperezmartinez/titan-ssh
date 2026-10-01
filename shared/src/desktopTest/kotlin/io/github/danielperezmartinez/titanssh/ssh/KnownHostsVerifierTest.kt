package io.github.danielperezmartinez.titanssh.ssh

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
    fun a_stored_host_accepts_only_its_stored_keys() = runTest {
        val stored = KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey1")
        val store = InMemoryKnownHostsStore(listOf(stored))
        var prompts = 0
        val rejections = mutableListOf<HostKeyRejection>()
        val verifier = KnownHostsVerifier(store, onRejected = { rejections += it }) { prompts++; true }

        assertTrue(verifier.verify(info("AAAAkey1")))
        val others = listOf(
            info("AAAAother"),
            info("AAAAecdsa", keyType = "ecdsa-sha2-nistp256"),
            info("AAAArsa", keyType = "ssh-rsa"),
            info("AAAAkey1", keyType = "ecdsa-sha2-nistp384"),
        )
        others.forEach { assertFalse(verifier.verify(it), "${it.keyType} ${it.publicKeyBase64}") }

        assertEquals(0, prompts)
        assertEquals(others.map<HostKeyInfo, HostKeyRejection> { HostKeyRejection.Changed(it, listOf(stored)) }, rejections)
        assertEquals(listOf(stored), store.entriesFor("host.example", 22), "nothing added")
    }

    @Test
    fun keys_are_per_host_and_port() = runTest {
        val store = InMemoryKnownHostsStore(listOf(KnownHostEntry("host.example", 2222, "ssh-ed25519", "AAAAkey1")))
        var prompts = 0
        val verifier = KnownHostsVerifier(store) { prompts++; true }

        assertTrue(verifier.verify(info("AAAAkey2")), "port 22 is another host")
        assertEquals(1, prompts)
    }

    @Test
    fun without_new_hosts_an_unknown_host_is_refused_unasked() = runTest {
        val store = InMemoryKnownHostsStore()
        var prompts = 0
        var rejection: HostKeyRejection? = null
        val verifier = KnownHostsVerifier(store, acceptNewHosts = false, onRejected = { rejection = it }) { prompts++; true }

        assertFalse(verifier.verify(info("AAAAkey1")))
        assertEquals(0, prompts)
        assertEquals(HostKeyRejection.NotTrusted(info("AAAAkey1")), rejection)
        assertTrue(store.entriesFor("host.example", 22).isEmpty())

        store.add(KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey1"))
        assertTrue(verifier.verify(info("AAAAkey1")), "a stored key still connects")
    }

    @Test
    fun a_declined_first_contact_is_reported() = runTest {
        var rejection: HostKeyRejection? = null
        val verifier = KnownHostsVerifier(InMemoryKnownHostsStore(), onRejected = { rejection = it }) { false }

        assertFalse(verifier.verify(info("AAAAkey1")))
        assertEquals(HostKeyRejection.Declined(info("AAAAkey1")), rejection)
    }

    @Test
    fun known_key_types_come_from_the_store() = runTest {
        val store = InMemoryKnownHostsStore(
            listOf(
                KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey1"),
                KnownHostEntry("host.example", 22, "ssh-rsa", "AAAAkey2"),
                KnownHostEntry("host.example", 22, "ssh-ed25519", "AAAAkey3"),
                KnownHostEntry("host.example", 2222, "ecdsa-sha2-nistp256", "AAAAkey4"),
            ),
        )
        val verifier = KnownHostsVerifier(store) { true }

        assertEquals(listOf("ssh-ed25519", "ssh-rsa"), verifier.knownKeyTypes("host.example", 22))
        assertEquals(emptyList<String>(), verifier.knownKeyTypes("other.example", 22))
        assertEquals(
            listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"),
            hostKeyAlgorithmsFor("ssh-rsa"),
        )
        assertEquals(listOf("ssh-ed25519"), hostKeyAlgorithmsFor("ssh-ed25519"))
    }

    @Test
    fun file_store_replace_keeps_every_other_line() = runBlocking {
        val file = File.createTempFile("titan-known-hosts", ".txt").apply { deleteOnExit() }
        file.writeText(
            """
            # comment
            host.example ssh-ed25519 AAAAold1
            [host.example]:2222 ssh-ed25519 AAAAport
            host.example ssh-rsa AAAAold2
            other.example ssh-ed25519 AAAAother
            """.trimIndent() + "\n",
        )
        val store = FileKnownHostsStore(file)

        store.replace(KnownHostEntry("host.example", 22, "ecdsa-sha2-nistp256", "AAAAnew"))

        val reopened = FileKnownHostsStore(file)
        assertEquals(listOf("AAAAnew"), reopened.entriesFor("host.example", 22).map { it.publicKeyBase64 })
        assertEquals(listOf("AAAAport"), reopened.entriesFor("host.example", 2222).map { it.publicKeyBase64 })
        assertEquals(listOf("AAAAother"), reopened.entriesFor("other.example", 22).map { it.publicKeyBase64 })
        assertTrue(file.readText().startsWith("# comment\n"))
    }

    @Test
    fun stored_entries_show_the_standard_fingerprint() {
        // A throwaway key; the fingerprint is what `ssh-keygen -lf` prints for it.
        val entry = KnownHostEntry(
            "host.example", 22, "ssh-ed25519",
            "AAAAC3NzaC1lZDI1NTE5AAAAIKqYB8WH4Sh8sFgGnrcw60lc483yRDf2kXV2m/utSWve",
        )
        assertEquals("SHA256:Js7wiL4vlZEVK7JH/bnGnguCZj4/9vFEJRALqOYpRVo", entry.fingerprintSha256)
        assertEquals("SHA256:?", KnownHostEntry("h", 22, "ssh-ed25519", "not base64!").fingerprintSha256)
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
