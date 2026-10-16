package io.github.danielperezmartinez.titanssh.update

import io.github.danielperezmartinez.titanssh.config.UpdatePolicy
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * [UpdateEngine] (ADR-0025): only a package listed in a manifest signed by a
 * key of the app, for a version above the running one, with the right size and
 * SHA-256, is ever handed to the installer; and installing follows the policy
 * without closing open tabs.
 */
class UpdateEngineTest {

    private val cache: File = Files.createTempDirectory("update-cache").toFile()
    private val key = Ed25519PrivateKeyParameters(SecureRandom())
    private val otherKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val current = AppVersion.parse("0.1.0-beta.14")!!

    @AfterTest
    fun cleanup() {
        cache.deleteRecursively()
    }

    // --- Fakes --------------------------------------------------------------

    private class Published(val version: String, val files: Map<String, ByteArray>, val draft: Boolean = false)

    private inner class FakeHttp(val releases: List<Published>) : UpdateHttp {
        var offline = false

        private fun body(url: String): ByteArray {
            if (offline) throw IOException("offline")
            if (url == API) return releaseList()
            val (version, name) = url.removePrefix("https://example.test/").split('/')
            return releases.first { it.version == version }.files[name] ?: throw IOException("404 $url")
        }

        private fun releaseList(): ByteArray = releases.joinToString(",", "[", "]") { r ->
            val assets = r.files.keys.joinToString(",") { name ->
                """{"name":"$name","browser_download_url":"https://example.test/${r.version}/$name","size":${r.files.getValue(name).size}}"""
            }
            """{"tag_name":"v${r.version}","draft":${r.draft},"prerelease":${r.version.contains('-')},"assets":[$assets]}"""
        }.encodeToByteArray()

        override suspend fun get(url: String, maxBytes: Long): ByteArray = body(url)

        override suspend fun download(url: String, dest: File, maxBytes: Long, onProgress: (Long) -> Unit): String {
            val bytes = body(url)
            if (bytes.size > maxBytes) throw IOException("too large")
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong())
            return sha256(bytes)
        }
    }

    private inner class FakePlatform(override val installsInBackground: Boolean = false) : UpdatePlatform {
        override val artifactKind = "msi"
        override val cacheDir: File = cache
        var permissionMissing = false
        val installs = mutableListOf<Pair<String, Boolean>>()
        var outcome: InstallOutcome = InstallOutcome.Started

        override fun needsPermission() = permissionMissing

        override suspend fun install(file: File, version: String, relaunch: Boolean): InstallOutcome {
            installs += version to relaunch
            return outcome
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sign(message: ByteArray, with: Ed25519PrivateKeyParameters = key): ByteArray =
        Ed25519Signer().run {
            init(true, with)
            update(message, 0, message.size)
            generateSignature()
        }

    private fun manifest(version: String, name: String, bytes: ByteArray, sha: String = sha256(bytes)): ByteArray =
        """{"schema":1,"version":"$version","artifacts":[{"kind":"msi","name":"$name","size":${bytes.size},"sha256":"$sha"},{"kind":"apk","name":"titan-ssh-$version.apk","size":1,"sha256":"00"}]}"""
            .encodeToByteArray()

    /** A Release as the workflow publishes it, with [tamper] applied to its files. */
    private fun release(
        version: String,
        signer: Ed25519PrivateKeyParameters = key,
        manifestVersion: String = version,
        tamper: (MutableMap<String, ByteArray>) -> Unit = {},
    ): Published {
        val name = "titan-ssh-$version-windows-x64.msi"
        val msi = "msi of $version".encodeToByteArray()
        val m = manifest(manifestVersion, name, msi)
        val files = mutableMapOf(name to msi, UpdateFiles.MANIFEST to m, UpdateFiles.SIGNATURE to sign(m, signer))
        tamper(files)
        return Published(version, files)
    }

    private fun engine(http: UpdateHttp, platform: UpdatePlatform, exits: MutableList<Unit> = mutableListOf()) = UpdateEngine(
        current = current,
        apiUrl = API,
        keys = listOf(key.generatePublicKey().encoded),
        platform = platform,
        http = http,
        scope = CoroutineScope(Dispatchers.Unconfined),
        exitApp = { exits += Unit },
    )

    // --- Versions -----------------------------------------------------------

    @Test
    fun versions_order_like_the_version_code() {
        val ordered = listOf("0.1.0-alpha.3", "0.1.0-beta.1", "0.1.0-beta.10", "0.1.0-rc.1", "0.1.0", "0.1.1-beta.1", "0.2.0", "1.0.0")
            .map { AppVersion.parse(it)!! }
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals("0.1.0-beta.10", AppVersion.parse("v0.1.0-beta.10").toString())
        assertNull(AppVersion.parse("0.0.0-dev"))
        assertNull(AppVersion.parse("1.2"))
        assertNull(AppVersion.parse("1.2.3-beta"))
    }

    // --- Signatures ---------------------------------------------------------

    /**
     * A manifest written and signed by tools/release/update-manifest.sh
     * (OpenSSL 3, throwaway key): what the release workflow signs is what the
     * app verifies.
     */
    @Test
    fun accepts_the_signature_of_the_release_script() {
        fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/update/openssl/$name")).use { it.readBytes() }
        val manifest = fixture("titan-update.json")
        val signature = fixture("titan-update.json.sig")
        val keys = UpdateEngine.decodeKeys(listOf(fixture("pub.b64").decodeToString().trim()))
        assertTrue(UpdateSignature.verify(manifest, signature, keys))
        assertTrue(!UpdateSignature.verify(manifest + ' '.code.toByte(), signature, keys))
        assertTrue(!UpdateSignature.verify(manifest, signature, listOf(key.generatePublicKey().encoded)))
        val parsed = UpdateFiles.json.decodeFromString<UpdateManifest>(manifest.decodeToString())
        assertEquals(setOf("msi", "apk", "deb", "rpm", "tar.gz"), parsed.artifacts.map { it.kind }.toSet())
    }

    // --- Checking -----------------------------------------------------------

    @Test
    fun downloads_the_highest_newer_version_betas_included() = runBlocking {
        val http = FakeHttp(listOf(release("0.1.0-beta.13"), release("0.1.0-beta.16"), release("0.1.0-beta.15")))
        val updater = engine(http, FakePlatform())
        updater.check()
        assertEquals(UpdateState.Ready("0.1.0-beta.16"), updater.state.value)
        val file = File(cache, "update/0.1.0-beta.16/titan-ssh-0.1.0-beta.16-windows-x64.msi")
        assertEquals("msi of 0.1.0-beta.16", file.readText())
    }

    @Test
    fun nothing_newer_is_up_to_date() = runBlocking {
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.14"), release("0.1.0-beta.2"))), FakePlatform())
        updater.check()
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test
    fun ignores_drafts_and_releases_without_a_manifest() = runBlocking {
        val draft = release("0.1.0-beta.20").let { Published(it.version, it.files, draft = true) }
        val bare = release("0.1.0-beta.19") { it.remove(UpdateFiles.SIGNATURE) }
        val updater = engine(FakeHttp(listOf(draft, bare, release("0.1.0-beta.15"))), FakePlatform())
        updater.check()
        assertEquals(UpdateState.Ready("0.1.0-beta.15"), updater.state.value)
    }

    @Test
    fun rejects_a_manifest_signed_by_another_key() = assertRejected(release("0.1.0-beta.15", signer = otherKey))

    @Test
    fun rejects_a_modified_manifest() = assertRejected(
        release("0.1.0-beta.15") { files ->
            files[UpdateFiles.MANIFEST] = files.getValue(UpdateFiles.MANIFEST).decodeToString()
                .replace("\"schema\":1", "\"schema\":1 ").encodeToByteArray()
        },
    )

    @Test
    fun rejects_an_old_signed_manifest_published_under_a_new_tag() =
        assertRejected(release("0.1.0-beta.15", manifestVersion = "0.1.0-beta.12"))

    @Test
    fun rejects_a_package_that_does_not_match_the_manifest() = assertRejected(
        release("0.1.0-beta.15") { it["titan-ssh-0.1.0-beta.15-windows-x64.msi"] = "evil".encodeToByteArray() },
    )

    private fun assertRejected(published: Published) = runBlocking {
        val platform = FakePlatform()
        val updater = engine(FakeHttp(listOf(published)), platform)
        updater.check()
        assertIs<UpdateState.Failed>(updater.state.value)
        updater.beforeExit()
        assertTrue(platform.installs.isEmpty())
        assertTrue(File(cache, "update").listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun a_failed_check_keeps_a_ready_update() = runBlocking {
        val http = FakeHttp(listOf(release("0.1.0-beta.15")))
        val updater = engine(http, FakePlatform())
        updater.check()
        http.offline = true
        updater.check()
        assertEquals(UpdateState.Ready("0.1.0-beta.15"), updater.state.value)
    }

    // --- Across restarts ----------------------------------------------------

    @Test
    fun a_verified_download_survives_a_restart_and_is_checked_again() = runBlocking {
        engine(FakeHttp(listOf(release("0.1.0-beta.15"))), FakePlatform()).check()

        val offline = FakeHttp(emptyList())
        val restarted = engine(offline, FakePlatform())
        restarted.start()
        assertEquals(UpdateState.Ready("0.1.0-beta.15"), restarted.state.value)

        File(cache, "update/0.1.0-beta.15/titan-ssh-0.1.0-beta.15-windows-x64.msi").writeText("tampered!!!!!!!!!!!!")
        val again = engine(offline, FakePlatform())
        again.start()
        assertEquals(UpdateState.Idle, again.state.value)
        assertTrue(File(cache, "update").listFiles().orEmpty().none { it.isDirectory })
    }

    @Test
    fun a_package_removed_before_installing_is_downloaded_again() = runBlocking {
        val platform = FakePlatform()
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform)
        updater.check()
        val file = File(cache, "update/0.1.0-beta.15/titan-ssh-0.1.0-beta.15-windows-x64.msi")
        assertTrue(file.delete())
        updater.beforeExit()
        assertTrue(platform.installs.isEmpty(), "nothing to install")
        assertEquals(UpdateState.Ready("0.1.0-beta.15"), updater.state.value)
        assertTrue(file.isFile, "downloaded again")
        updater.beforeExit()
        assertEquals(listOf("0.1.0-beta.15" to false), platform.installs)
    }

    // --- Installing ---------------------------------------------------------

    @Test
    fun desktop_installs_at_exit_only_with_the_automatic_policy() = runBlocking {
        val platform = FakePlatform()
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform)
        updater.check()
        updater.setPolicy(UpdatePolicy.ASK)
        updater.beforeExit()
        assertTrue(platform.installs.isEmpty())
        updater.setPolicy(UpdatePolicy.AUTO)
        updater.beforeExit()
        assertEquals(listOf("0.1.0-beta.15" to false), platform.installs)
    }

    @Test
    fun install_now_relaunches_and_exits_the_desktop_app() = runBlocking {
        val platform = FakePlatform()
        val exits = mutableListOf<Unit>()
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform, exits)
        updater.check()
        updater.setPolicy(UpdatePolicy.ASK)
        updater.installNow()
        assertEquals(listOf("0.1.0-beta.15" to true), platform.installs)
        assertEquals(1, exits.size)
    }

    @Test
    fun automatic_installs_stop_after_repeated_failures() = runBlocking {
        val platform = FakePlatform()
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform)
        updater.check()
        repeat(UpdateEngine.MAX_AUTO_ATTEMPTS + 2) { updater.beforeExit() }
        assertEquals(UpdateEngine.MAX_AUTO_ATTEMPTS, platform.installs.size)
    }

    @Test
    fun android_installs_in_the_background_with_no_tab_open() = runBlocking {
        val platform = FakePlatform(installsInBackground = true)
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform)
        updater.setOpenTabs(2)
        updater.check()
        updater.setForeground(false)
        assertTrue(platform.installs.isEmpty(), "never while a tab is open")
        updater.setForeground(true)
        updater.setOpenTabs(0)
        assertTrue(platform.installs.isEmpty(), "never on screen")
        updater.setPolicy(UpdatePolicy.ASK)
        updater.setForeground(false)
        assertTrue(platform.installs.isEmpty(), "never when it has to ask")
        updater.setPolicy(UpdatePolicy.AUTO)
        assertEquals(listOf("0.1.0-beta.15" to false), platform.installs)
        updater.beforeExit()
        assertEquals(1, platform.installs.size, "Android does not install at exit")
    }

    @Test
    fun android_waits_for_the_permission_and_the_confirmation() = runBlocking {
        val platform = FakePlatform(installsInBackground = true).apply { permissionMissing = true }
        val updater = engine(FakeHttp(listOf(release("0.1.0-beta.15"))), platform)
        updater.check()
        assertEquals(UpdateState.Ready("0.1.0-beta.15", needsPermission = true), updater.state.value)
        updater.setForeground(false)
        assertTrue(platform.installs.isEmpty())

        platform.permissionMissing = false
        platform.outcome = InstallOutcome.AwaitingUser
        updater.setForeground(true)
        updater.setForeground(false)
        assertEquals(UpdateState.Ready("0.1.0-beta.15", needsConfirmation = true), updater.state.value)
    }

    private companion object {
        const val API = "https://api.example.test/releases"
    }
}
