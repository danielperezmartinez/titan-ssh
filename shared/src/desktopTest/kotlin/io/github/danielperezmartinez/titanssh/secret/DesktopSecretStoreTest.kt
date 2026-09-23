package io.github.danielperezmartinez.titanssh.secret

import com.github.javakeyring.Keyring
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round-trips the desktop [SecretStore] against the real OS secret store
 * (Windows Credential Store / Linux Secret Service). Skips itself where no
 * OS-backed keyring exists (e.g. a headless CI box), because the store is
 * designed to fail closed there rather than persist plaintext.
 */
class DesktopSecretStoreTest {

    private val ref = SecretRef("titanssh.test.${System.nanoTime()}")
    private val store: SecretStore = createSecretStore()

    private fun keyringAvailable(): Boolean =
        try {
            Keyring.create(); true
        } catch (e: Throwable) {
            false
        }

    @AfterTest
    fun cleanup() = runTest {
        if (keyringAvailable()) runCatching { store.remove(ref) }
    }

    @Test
    fun stores_retrieves_and_removes_a_secret() = runTest {
        if (!keyringAvailable()) return@runTest // no OS keyring here; nothing to prove

        val secret = "correct horse battery staple".encodeToByteArray()

        assertFalse(store.contains(ref), "ref should be absent before storing")

        store.put(ref, secret)
        assertTrue(store.contains(ref), "ref should exist after storing")
        assertContentEquals(secret, store.get(ref), "round-tripped bytes must match")

        store.remove(ref)
        assertFalse(store.contains(ref), "ref should be gone after removing")
        assertNull(store.get(ref), "get on a removed ref returns null")
    }
}
