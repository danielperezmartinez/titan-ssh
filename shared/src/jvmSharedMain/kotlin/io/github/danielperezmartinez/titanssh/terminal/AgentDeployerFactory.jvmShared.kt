package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.BuildInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The `titan-agent` binaries (ADR-0010). The `buildAgentBinaries` Gradle task
 * cross-compiles every target, bundles the main ones as JVM resources under
 * `/agent/` (loaded via the classloader, so both the desktop and the Android app
 * ship them) and writes `/agent/SHA256SUMS` with the digest of **every** target.
 * The rest are published as assets of the version's GitHub Release and fetched
 * on demand by [AgentDownloader], which only accepts bytes matching that pinned
 * digest. [VERSION] is the single app version, also stamped into the binary; it
 * drives the versioned install path (ADR-0008 §5).
 */
object AgentBinaries {
    const val VERSION: String = BuildInfo.VERSION

    /** Bytes of the bundled agent for [target], or null if it is not bundled. */
    fun bundled(target: AgentTarget): ByteArray? =
        AgentBinaries::class.java.getResourceAsStream("/agent/${target.fileName}")
            ?.use { it.readBytes() }

    /**
     * The SHA-256 of every target this build compiled, keyed by file name
     * (`titan-agent-<slug>[.exe]`). Empty for a build made without Go.
     */
    val checksums: Map<String, String> by lazy {
        val text = AgentBinaries::class.java.getResourceAsStream("/agent/SHA256SUMS")
            ?.use { it.readBytes().decodeToString() }
            ?: return@lazy emptyMap()
        AgentChecksums.parse(text)
    }
}

/** Parses the `SHA256SUMS` resource (`<hex>  <file name>` per line). */
object AgentChecksums {
    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val hex = line.substringBefore(' ').lowercase()
                val name = line.substringAfter(' ').trim().removePrefix("*")
                if (hex.length == 64 && name.isNotEmpty()) name to hex else null
            }
            .toMap()
}

/**
 * Fetches the agent binaries that are not bundled from the version's GitHub
 * Release (ADR-0010 §3), verifying each against the digest pinned in the app
 * before anything uses it. Only the version and the target reach the server.
 * Verified binaries are cached in [cacheDir] (when there is one), and files of
 * other versions are removed from it. Any failure yields null, so the session
 * degrades to level 2/1.
 */
class AgentDownloader(
    private val version: String,
    private val checksums: Map<String, String>,
    private val cacheDir: File?,
    private val fetch: suspend (url: String) -> ByteArray = ::httpGet,
) {
    private val mutex = Mutex()

    suspend fun load(target: AgentTarget): ByteArray? = mutex.withLock {
        val expected = checksums[target.fileName] ?: return@withLock null
        val cached = cacheDir?.let { File(it, assetName(version, target)) }
        cached?.takeIf { it.isFile }?.let { file ->
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes != null && sha256Hex(bytes) == expected) return@withLock bytes
            withContext(Dispatchers.IO) { file.delete() }
        }
        val bytes = runCatching { fetch(downloadUrl(version, target)) }.getOrNull() ?: return@withLock null
        if (sha256Hex(bytes) != expected) return@withLock null
        if (cached != null) withContext(Dispatchers.IO) { store(cached, bytes) }
        bytes
    }

    /** Writes [bytes] atomically and drops the cached binaries of other versions. */
    private fun store(file: File, bytes: ByteArray) {
        runCatching {
            val dir = file.parentFile
            dir.mkdirs()
            val current = AgentInstall.SUPPORTED_TARGETS.map { assetName(version, it) }.toSet()
            dir.listFiles()?.filter { it.name.startsWith("titan-agent-") && it.name !in current }
                ?.forEach { it.delete() }
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        /** Base of the Release download URLs; the tag is `v<version>`. */
        const val RELEASES_URL = "https://github.com/danielperezmartinez/titan-ssh/releases/download"

        /** Upper bound for a download: the agent is a few megabytes. */
        const val MAX_BYTES = 32 * 1024 * 1024

        /** Name of the Release asset: `titan-agent-<version>-<slug>[.exe]`. */
        fun assetName(version: String, target: AgentTarget): String =
            "titan-agent-$version-${target.slug}${target.executableSuffix}"

        fun downloadUrl(version: String, target: AgentTarget): String =
            "$RELEASES_URL/v$version/${assetName(version, target)}"

        private suspend fun httpGet(url: String): ByteArray = withContext(Dispatchers.IO) {
            // GitHub answers with a redirect to its download host (https to
            // https), which HttpURLConnection follows by itself.
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "titan-ssh/${BuildInfo.VERSION}")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode} for $url")
                }
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("download larger than $MAX_BYTES bytes")
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * Builds the deployer over [AgentInstaller]: the bundled binary when there is
 * one, otherwise a download cached in [cacheDir]. A development build has no
 * Release to download from, so there only the bundled targets get level 3.
 * Non-null: when no binary is available for the detected target it simply
 * yields nothing and the tab degrades to level 2/1.
 */
internal fun bundledAgentDeployer(cacheDir: File?): AgentDeployer {
    val downloader = AgentDownloader(AgentBinaries.VERSION, AgentBinaries.checksums, cacheDir)
    return agentDeployer(version = AgentBinaries.VERSION) { target ->
        AgentBinaries.bundled(target) ?: if (BuildInfo.IS_RELEASE) downloader.load(target) else null
    }
}
