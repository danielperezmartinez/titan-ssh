package io.github.danielperezmartinez.titanssh.update

import io.github.danielperezmartinez.titanssh.config.UpdatePolicy
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The updater of ADR-0025, the same on Android and the desktop; [platform]
 * says which package this install takes and how it is installed.
 *
 * A check reads the Releases list at [apiUrl] and takes the highest version
 * above [current], betas included, that publishes a manifest. The package is
 * used only if the manifest is signed by one of [keys], names that version and
 * lists a package of this install's kind whose size and SHA-256 match what was
 * downloaded. A verified download waits in [UpdatePlatform.cacheDir] across
 * restarts and is checked again before it is used.
 *
 * Installing never closes a tab: with [UpdatePolicy.AUTO] the desktop installs
 * at exit and Android while in the background with no tab open; with
 * [UpdatePolicy.ASK] only [installNow] does. An automatic install that keeps
 * failing stops after [MAX_AUTO_ATTEMPTS] tries, and [installNow] still works.
 */
class UpdateEngine(
    private val current: AppVersion,
    private val apiUrl: String,
    private val keys: List<ByteArray>,
    private val platform: UpdatePlatform,
    private val http: UpdateHttp = UrlConnectionHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val firstCheckDelay: Duration = 15.seconds,
    private val checkInterval: Duration = 6.hours,
    private val exitApp: () -> Unit = { AppUpdates.exitApp?.invoke() },
) : AppUpdater {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state
    override val installsInBackground: Boolean get() = platform.installsInBackground

    private val lock = Mutex()
    private var started = false

    @Volatile private var policy = UpdatePolicy.AUTO
    @Volatile private var openTabs = 0
    @Volatile private var foreground = true
    @Volatile private var pending: Pending? = null

    /** A verified package waiting to be installed. */
    private class Pending(val version: AppVersion, val dir: File, val file: File, val sha256: String)

    private val updateDir get() = File(platform.cacheDir, "update")

    override fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            lock.withLock { loadPending() }
            maybeInstallInBackground()
            delay(firstCheckDelay)
            while (true) {
                check()
                delay(checkInterval)
            }
        }
    }

    override fun checkNow() {
        scope.launch { check() }
    }

    override fun setPolicy(policy: UpdatePolicy) {
        this.policy = policy
        maybeInstallInBackground()
    }

    override fun setOpenTabs(count: Int) {
        openTabs = count
        maybeInstallInBackground()
    }

    override fun setForeground(visible: Boolean) {
        foreground = visible
        pending?.let { publishReady(it) }
        maybeInstallInBackground()
    }

    override fun installNow() {
        val ready = pending ?: return
        if (platform.needsPermission()) {
            platform.requestPermission()
            return
        }
        scope.launch { install(ready, relaunch = true, automatic = false) }
    }

    override fun requestInstallPermission() = platform.requestPermission()

    override fun beforeExit() {
        val ready = pending ?: return
        if (policy != UpdatePolicy.AUTO || platform.installsInBackground) return
        if (attempts(ready.dir) >= MAX_AUTO_ATTEMPTS) return
        // The helper has to be running before the process ends; starting it is quick.
        runBlocking {
            withTimeoutOrNull(10.seconds) { install(ready, relaunch = false, automatic = true) }
        }
    }

    // --- Checking -----------------------------------------------------------

    /** One check; skipped while another check or an install is running. */
    internal suspend fun check() {
        if (!lock.tryLock()) return
        try {
            if (pending == null) _state.value = UpdateState.Checking
            val failure = runCatching { checkLocked() }.exceptionOrNull()
            if (failure != null) {
                log("check failed: $failure")
                val ready = pending
                if (ready != null) publishReady(ready) else _state.value = UpdateState.Failed(describe(failure))
            }
        } finally {
            lock.unlock()
        }
        maybeInstallInBackground()
    }

    private suspend fun checkLocked() {
        val releases = UpdateFiles.json.decodeFromString<List<GithubRelease>>(
            http.get(apiUrl, MAX_LIST_BYTES).decodeToString(),
        )
        val best = releases
            .filter { !it.draft }
            .mapNotNull { release -> AppVersion.parse(release.tagName)?.let { it to release } }
            .filter { (version, release) ->
                version > current &&
                    release.assets.any { it.name == UpdateFiles.MANIFEST } &&
                    release.assets.any { it.name == UpdateFiles.SIGNATURE }
            }
            .maxByOrNull { it.first }
        val ready = pending
        if (best == null || (ready != null && ready.version >= best.first)) {
            if (ready != null) publishReady(ready) else _state.value = UpdateState.UpToDate
            return
        }
        val (version, release) = best
        fun asset(name: String) = release.assets.first { it.name == name }

        val manifestBytes = http.get(asset(UpdateFiles.MANIFEST).url, MAX_MANIFEST_BYTES)
        val signature = http.get(asset(UpdateFiles.SIGNATURE).url, MAX_SIGNATURE_BYTES)
        if (!UpdateSignature.verify(manifestBytes, signature, keys)) {
            throw UpdateException("la firma de la versión $version no es válida")
        }
        val manifest = UpdateFiles.json.decodeFromString<UpdateManifest>(manifestBytes.decodeToString())
        if (AppVersion.parse(manifest.version) != version) {
            throw UpdateException("el manifiesto no corresponde a la versión $version")
        }
        val artifact = manifest.artifacts.firstOrNull { it.kind == platform.artifactKind }
            ?: throw UpdateException("la versión $version no trae un paquete ${platform.artifactKind}")
        if (!UpdateFiles.isSafeName(artifact.name) || artifact.size !in 1..MAX_PACKAGE_BYTES) {
            throw UpdateException("el paquete de la versión $version no es válido")
        }
        val packageAsset = release.assets.firstOrNull { it.name == artifact.name }
            ?: throw UpdateException("falta ${artifact.name} en la versión $version")

        _state.value = UpdateState.Downloading(version.toString(), 0f)
        val dir = File(updateDir, version.toString())
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw UpdateException("no se puede escribir en ${dir.path}")
        val partial = File(dir, "${artifact.name}.part")
        var lastShown = -1
        val sha = http.download(packageAsset.url, partial, artifact.size) { written ->
            val percent = (written * 100 / artifact.size).toInt()
            if (percent != lastShown) {
                lastShown = percent
                _state.value = UpdateState.Downloading(version.toString(), (written.toFloat() / artifact.size).coerceAtMost(1f))
            }
        }
        if (partial.length() != artifact.size || !sha.equals(artifact.sha256, ignoreCase = true)) {
            dir.deleteRecursively()
            throw UpdateException("la descarga de la versión $version no coincide con su manifiesto")
        }
        val file = File(dir, artifact.name)
        if (!partial.renameTo(file)) throw UpdateException("no se puede guardar ${file.path}")
        File(dir, UpdateFiles.MANIFEST).writeBytes(manifestBytes)
        File(dir, UpdateFiles.SIGNATURE).writeBytes(signature)
        removeOtherVersions(keep = dir)
        log("downloaded $version (${artifact.name})")
        val next = Pending(version, dir, file, artifact.sha256.lowercase())
        pending = next
        publishReady(next)
    }

    /**
     * Picks up a download a previous run left verified, checking it again:
     * signature, version above the running one and the package's digest.
     * Anything else in the folder (an installed version, a broken download)
     * is removed, except the logs: the installer's own ones live next to
     * [LOG_FILE] so they outlive the version they installed.
     */
    private fun loadPending() {
        val found = updateDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            .mapNotNull { dir -> runCatching { verifiedPending(dir) }.getOrNull() }
            .maxByOrNull { it.version }
        updateDir.listFiles()
            ?.filter { it != found?.dir && !(it.isFile && it.name.endsWith(".log")) }
            ?.forEach { it.deleteRecursively() }
        pending = found
        if (found != null) {
            val failure = platform.previousFailure(found.dir)
            if (failure != null) log("previous install of ${found.version} failed: $failure")
            publishReady(found, error = failure)
        }
    }

    private fun verifiedPending(dir: File): Pending? {
        val manifestBytes = File(dir, UpdateFiles.MANIFEST).takeIf { it.isFile }?.readBytes() ?: return null
        val signature = File(dir, UpdateFiles.SIGNATURE).takeIf { it.isFile }?.readBytes() ?: return null
        if (!UpdateSignature.verify(manifestBytes, signature, keys)) return null
        val manifest = UpdateFiles.json.decodeFromString<UpdateManifest>(manifestBytes.decodeToString())
        val version = AppVersion.parse(manifest.version)?.takeIf { it > current && it.toString() == dir.name } ?: return null
        val artifact = manifest.artifacts.firstOrNull { it.kind == platform.artifactKind } ?: return null
        if (!UpdateFiles.isSafeName(artifact.name)) return null
        val file = File(dir, artifact.name).takeIf { it.isFile && it.length() == artifact.size } ?: return null
        if (!UpdateFiles.sha256Hex(file).equals(artifact.sha256, ignoreCase = true)) return null
        return Pending(version, dir, file, artifact.sha256.lowercase())
    }

    private fun removeOtherVersions(keep: File) {
        updateDir.listFiles()?.filter { it.isDirectory && it != keep }?.forEach { it.deleteRecursively() }
    }

    // --- Installing ---------------------------------------------------------

    private fun maybeInstallInBackground() {
        val ready = pending ?: return
        if (!platform.installsInBackground || policy != UpdatePolicy.AUTO) return
        if (foreground || openTabs > 0 || platform.needsPermission()) return
        if (attempts(ready.dir) >= MAX_AUTO_ATTEMPTS) return
        scope.launch { install(ready, relaunch = false, automatic = true) }
    }

    private suspend fun install(ready: Pending, relaunch: Boolean, automatic: Boolean) {
        if (!lock.tryLock()) return
        val outcome = try {
            if (pending !== ready) return
            // Removed from under us (a cleaner, the user): forget it and look again.
            if (!ready.file.isFile) {
                log("package of ${ready.version} is gone; checking again")
                pending = null
                ready.dir.deleteRecursively()
                _state.value = UpdateState.Idle
                null
            } else {
                if (automatic) recordAttempt(ready.dir)
                _state.value = UpdateState.Installing(ready.version.toString())
                log("installing ${ready.version} (automatic=$automatic, relaunch=$relaunch)")
                runCatching { platform.install(ready.file, ready.sha256, ready.version.toString(), relaunch) }
                    .getOrElse { InstallOutcome.Failed(describe(it)) }
            }
        } finally {
            lock.unlock()
        }
        // null: the package was gone, so look for the update again.
        if (outcome == null) {
            check()
            return
        }
        log("install of ${ready.version}: $outcome")
        when (outcome) {
            InstallOutcome.Started -> if (relaunch && !platform.installsInBackground) exitApp()
            InstallOutcome.AwaitingUser -> _state.value = UpdateState.Ready(ready.version.toString(), needsConfirmation = true)
            InstallOutcome.NeedsPermission -> publishReady(ready)
            is InstallOutcome.Failed -> publishReady(ready, error = outcome.message)
        }
    }

    /** Shows [ready] as installable, keeping what the last state knew about it. */
    private fun publishReady(ready: Pending, error: String? = null) {
        val version = ready.version.toString()
        val previous = (_state.value as? UpdateState.Ready)?.takeIf { it.version == version }
        _state.value = UpdateState.Ready(
            version = version,
            needsPermission = platform.needsPermission(),
            needsConfirmation = previous?.needsConfirmation == true,
            lastError = error ?: previous?.lastError,
        )
    }

    private fun attempts(dir: File): Int =
        runCatching { File(dir, ATTEMPTS_FILE).readText().trim().toInt() }.getOrDefault(0)

    private fun recordAttempt(dir: File) {
        runCatching { File(dir, ATTEMPTS_FILE).writeText((attempts(dir) + 1).toString()) }
    }

    // --- Diagnostics --------------------------------------------------------

    /** A short local log, for when an update does not arrive; never leaves the device. */
    private fun log(line: String) {
        runCatching {
            updateDir.mkdirs()
            val file = File(updateDir, LOG_FILE)
            if (file.length() > MAX_LOG_BYTES) file.writeText(file.readLines().takeLast(100).joinToString("\n", postfix = "\n"))
            file.appendText("${java.time.Instant.now()} $line\n")
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UpdateException -> error.message.orEmpty()
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "sin conexión con GitHub"
        else -> error.message ?: error::class.simpleName.orEmpty()
    }

    private class UpdateException(message: String) : Exception(message)

    companion object {
        const val MAX_AUTO_ATTEMPTS = 3
        const val LOG_FILE = "update.log"
        private const val ATTEMPTS_FILE = "attempts"
        private const val MAX_LIST_BYTES = 4L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 256L * 1024
        private const val MAX_SIGNATURE_BYTES = 1024L
        private const val MAX_PACKAGE_BYTES = 1024L * 1024 * 1024
        private const val MAX_LOG_BYTES = 64L * 1024

        /** The trusted keys of [io.github.danielperezmartinez.titanssh.BuildInfo.UPDATE_KEYS]. */
        fun decodeKeys(base64: List<String>): List<ByteArray> =
            base64.mapNotNull { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                .filter { it.size == 32 }
    }
}
