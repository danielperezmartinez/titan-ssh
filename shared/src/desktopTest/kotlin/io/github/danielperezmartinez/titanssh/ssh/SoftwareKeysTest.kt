package io.github.danielperezmartinez.titanssh.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.schmizz.sshj.SSHClient

/** In-app ed25519 key pairs ([[Instalar la clave pública en el host desde la app]]). */
class SoftwareKeysTest {

    @Test
    fun generates_an_openssh_ed25519_pair_with_its_comment() {
        val pair = generateEd25519KeyPair("titan-ssh")
        val pem = String(pair.privateKeyPem)
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertTrue(pem.endsWith("-----END OPENSSH PRIVATE KEY-----\n"))
        assertTrue(pem.lines().all { it.length <= 70 })
        assertTrue(pair.publicLine.matches(Regex("ssh-ed25519 [A-Za-z0-9+/]+=* titan-ssh")), pair.publicLine)
    }

    @Test
    fun sshj_reads_the_private_key_back_to_the_same_public_key() {
        val pair = generateEd25519KeyPair("c")
        val keys = SSHClient(sshjConfig(keepAlive = false)).loadKeys(String(pair.privateKeyPem), null, null)
        assertEquals(pair.publicLine.removeSuffix(" c"), sshPublicKeyOpenSshLine(keys.public))
        assertEquals(pair.publicLine.removeSuffix(" c"), publicLineOfPrivateKey(pair.privateKeyPem, null))
    }

    @Test
    fun every_pair_is_new() {
        assertNotEquals(generateEd25519KeyPair("").publicLine, generateEd25519KeyPair("").publicLine)
        assertEquals(2, generateEd25519KeyPair(" ").publicLine.split(' ').size)
    }

    @Test
    fun a_text_that_is_no_key_is_refused() {
        assertFailsWith<IllegalArgumentException> { publicLineOfPrivateKey("hola".toCharArray(), null) }
    }

    @Test
    fun the_identity_of_a_line_ignores_options_and_comment() {
        val id = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA"
        assertEquals(id, authorizedKeyIdentity("$id user@pc"))
        assertEquals(id, authorizedKeyIdentity("  $id"))
        assertEquals(id, authorizedKeyIdentity("no-pty,from=\"*.example.org\" $id comment with spaces"))
        assertEquals("ecdsa-sha2-nistp256 AAAAE2", authorizedKeyIdentity("ecdsa-sha2-nistp256 AAAAE2"))
        assertNull(authorizedKeyIdentity("# a comment"))
        assertNull(authorizedKeyIdentity(""))
        assertNull(authorizedKeyIdentity("ssh-ed25519"))
    }
}
