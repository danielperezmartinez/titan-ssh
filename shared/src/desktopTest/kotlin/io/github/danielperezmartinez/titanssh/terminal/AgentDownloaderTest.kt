package io.github.danielperezmartinez.titanssh.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [AgentDownloader] (ADR-0010 §2): a download is only accepted when it matches
 * the digest pinned in the app, verified copies are cached per version, and any
 * failure yields null so the session degrades.
 */
class AgentDownloaderTest {

    private val target = AgentTarget("linux", "riscv64")
    private val good = ByteArray(1000) { it.toByte() }
    private val pinned = mapOf(target.fileName to sha256(good))
    private val cache: File = Files.createTempDirectory("agent-cache").toFile()

    @AfterTest
    fun cleanup() {
        cache.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun downloads_from_the_versions_release_and_caches_it() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val downloader = AgentDownloader("1.2.0-beta.1", pinned, cache) { url -> urls += url; good }

        assertContentEquals(good, downloader.load(target))
        assertContentEquals(good, downloader.load(target))

        assertEquals(
            listOf("https://github.com/danielperezmartinez/titan-ssh/releases/download/v1.2.0-beta.1/titan-agent-1.2.0-beta.1-linux-riscv64"),
            urls,
            "the second load comes from the cache",
        )
        assertTrue(File(cache, "titan-agent-1.2.0-beta.1-linux-riscv64").isFile)
    }

    @Test
    fun rejects_a_download_that_does_not_match_the_pinned_digest() = runBlocking<Unit> {
        val tampered = good.copyOf().also { it[0] = 42 }
        val downloader = AgentDownloader("1.2.0", pinned, cache) { tampered }
        assertNull(downloader.load(target))
        assertFalse(File(cache, "titan-agent-1.2.0-linux-riscv64").exists(), "nothing unverified is cached")
    }

    @Test
    fun a_corrupted_cache_entry_is_fetched_again() = runBlocking<Unit> {
        File(cache, "titan-agent-1.2.0-linux-riscv64").writeBytes(byteArrayOf(9, 9, 9))
        var fetched = 0
        val downloader = AgentDownloader("1.2.0", pinned, cache) { fetched++; good }
        assertContentEquals(good, downloader.load(target))
        assertEquals(1, fetched)
    }

    @Test
    fun caching_a_version_drops_the_others() = runBlocking<Unit> {
        val old = File(cache, "titan-agent-1.1.0-linux-riscv64").apply { writeBytes(byteArrayOf(1)) }
        // Shares the `titan-agent-1.2.0-` prefix, but it is another version.
        val beta = File(cache, "titan-agent-1.2.0-beta.1-linux-riscv64").apply { writeBytes(byteArrayOf(1)) }
        AgentDownloader("1.2.0", pinned, cache) { good }.load(target)
        assertFalse(old.exists())
        assertFalse(beta.exists())
    }

    @Test
    fun failures_and_unpinned_targets_yield_null() = runBlocking<Unit> {
        assertNull(AgentDownloader("1.2.0", pinned, cache) { throw IOException("offline") }.load(target))
        var fetched = false
        val unpinned = AgentDownloader("1.2.0", emptyMap(), cache) { fetched = true; good }
        assertNull(unpinned.load(target))
        assertFalse(fetched, "a target without a pinned digest is never downloaded")
        // Without a cache directory it still works, it just downloads every time.
        assertContentEquals(good, AgentDownloader("1.2.0", pinned, null) { good }.load(target))
    }

    @Test
    fun parses_the_pinned_checksums() {
        val hex = "b".repeat(64)
        assertEquals(
            mapOf("titan-agent-linux-amd64" to hex, "titan-agent-windows-amd64.exe" to hex),
            AgentChecksums.parse("$hex  titan-agent-linux-amd64\n${hex.uppercase()} *titan-agent-windows-amd64.exe\n\njunk\n"),
        )
    }
}
