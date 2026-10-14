package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshSftpFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** What the browser shows of the current folder. */
data class SftpListing(
    /** The folder shown; null until the start folder is known. */
    val path: String? = null,
    /** Its entries as listed, unsorted: the view sorts and filters ([SftpPaths.sorted]). */
    val entries: List<SftpEntry> = emptyList(),
    val loading: Boolean = true,
    /** Why the folder could not be listed; the entries are then the last ones shown. */
    val error: String? = null,
    /** Why the browser did not start where it was asked to; shown with the start folder only. */
    val notice: String? = null,
)

/** One download of the SFTP browser, for the transfers tray. */
data class SftpTransfer(
    val id: Int,
    val name: String,
    val remotePath: String,
    /** Total bytes, when the server reported them. */
    val size: Long?,
    val transferred: Long = 0,
    val state: State = State.RUNNING,
    /** Where it is saved ([DownloadTarget.location]), once the file exists. */
    val location: String? = null,
    /** Why it failed. */
    val error: String? = null,
) {
    enum class State {
        RUNNING,

        /** The connection dropped: it goes on from [transferred] once the tab reconnects. */
        WAITING,
        DONE,
        FAILED,
        CANCELLED,
    }

    val finished: Boolean get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
}

/**
 * The state of an SFTP tab (ADR-0021): the folder on show and the downloads.
 * It outlives the tab's connections. [SessionTab] [attach]es each new SFTP
 * channel and [detach]es it when the connection drops; meanwhile the listing
 * waits, and a download in progress pauses ([SftpTransfer.State.WAITING]) and
 * goes on from its last written byte on the next channel.
 *
 * A request that fails without being the server's answer ([SshSftpFailed]),
 * typically sshj's request timeout on a dead link, tells the tab through the
 * `onLost` of [attach] that the connection is gone: the keepalive takes much
 * longer to notice. The tab reconnects and attaches a new channel.
 *
 * Read-only: it lists and downloads, nothing else.
 */
class SftpBrowser(
    private val scope: CoroutineScope,
    /** The session's working directory, where the browser starts ([SftpPaths.initial]). */
    private val initialDirectory: String?,
    private val store: () -> DownloadStore,
    /**
     * Shown with the start folder ([SftpListing.notice]) when the caller could
     * not tell where to start, e.g. a terminal's directory that is unknown.
     */
    private val startNote: String? = null,
    /** Runs the blocking [DownloadTarget] calls; tests pass their own. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * How many reconnections in a row a download or a listing asks for without
     * getting anywhere before it gives up: an error that keeps coming back on
     * fresh connections is not the network.
     */
    private val maxRetries: Int = MAX_RETRIES,
) {
    private val client = MutableStateFlow<SshSftp?>(null)

    /** What to call when [client] turns out to be dead. */
    private var onLost: (() -> Unit)? = null

    /** Listing errors in a row that asked for a reconnection. */
    private var listingRetries = 0

    private val _listing = MutableStateFlow(SftpListing())
    val listing: StateFlow<SftpListing> = _listing.asStateFlow()

    private val _transfers = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers: StateFlow<List<SftpTransfer>> = _transfers.asStateFlow()

    /** The user's folder on the server, once known. */
    private var home: String? = null

    private var loadJob: Job? = null
    private val jobs = mutableMapOf<Int, Job>()
    private val targets = mutableMapOf<Int, DownloadTarget>()
    private var nextId = 1
    private var closed = false

    /**
     * A new channel is up: show where the user was (the start folder the first
     * time). [onLost] is called once if the browser finds the connection dead
     * before the tab does.
     */
    fun attach(sftp: SshSftp, onLost: () -> Unit = {}) {
        if (closed) return
        this.onLost = onLost
        client.value = sftp
        load(_listing.value.path)
    }

    /** [sftp]'s connection is gone: whatever uses it waits for the next one. */
    fun detach(sftp: SshSftp) {
        if (client.compareAndSet(sftp, null)) onLost = null
    }

    /** A request on [sftp] failed as on a dead connection: drop it and tell the tab. */
    private fun reportLost(sftp: SshSftp) {
        if (!client.compareAndSet(sftp, null)) return
        val callback = onLost
        onLost = null
        callback?.invoke()
    }

    /** Opens the folder [path] (any form the server understands; it is canonicalized). */
    fun open(path: String) = load(path)

    /** Opens the folder above the current one. */
    fun up() {
        val parent = _listing.value.path?.let(SftpPaths::parent) ?: return
        load(parent)
    }

    /** Opens the user's folder on the server. */
    fun goHome() {
        home?.let(::load)
    }

    /** Lists the current folder again. */
    fun refresh() = load(_listing.value.path)

    /**
     * Lists [path] (the start folder when null) as soon as there is a channel.
     * A newer request replaces one still running.
     */
    private fun load(path: String?) {
        if (closed) return
        loadJob?.cancel()
        _listing.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            val sftp = client.first { it != null }!!
            try {
                val (target, notice) = if (path != null) path to null else startFolder(sftp)
                val real = sftp.canonicalize(target)
                val entries = sftp.listEntries(real)
                _listing.value = SftpListing(path = real, entries = entries, loading = false, notice = notice)
                listingRetries = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dropped connection lists again on the next attach.
                if (client.value !== sftp) return@launch
                if (e !is SshSftpFailed && listingRetries++ < maxRetries) {
                    reportLost(sftp)
                    return@launch
                }
                // The server's answer for this folder.
                listingRetries = 0
                _listing.update { it.copy(loading = false, error = listingError(e, path)) }
            }
        }
    }

    /**
     * The session's working directory if it can be opened, else the user's
     * folder, with the note that says why it is not where it was asked to be.
     */
    private suspend fun startFolder(sftp: SshSftp): Pair<String, String?> {
        val home = home ?: sftp.canonicalize(".").also { home = it }
        val wanted = SftpPaths.initial(initialDirectory, home)
        if (wanted == home) return home to startNote
        val found = runCatching { sftp.stat(wanted) }.getOrNull()
        return if (found?.isDirectory == true) wanted to null else home to "No se puede abrir $wanted: se muestra la carpeta de inicio"
    }

    /**
     * Downloads [entry] to the device. It runs until it finishes, fails or is
     * cancelled, riding out the tab's reconnections.
     */
    fun download(entry: SftpEntry) {
        if (closed || entry.isDirectory) return
        val id = nextId++
        _transfers.update { it + SftpTransfer(id, entry.name, entry.path, entry.size) }
        jobs[id] = scope.launch { runDownload(id, entry) }
    }

    /** Stops a download in progress and deletes what it had written. */
    fun cancel(id: Int) {
        jobs.remove(id)?.cancel()
    }

    /** Removes a finished download from the tray (the file stays). */
    fun dismiss(id: Int) {
        if (_transfers.value.firstOrNull { it.id == id }?.finished != true) return
        _transfers.update { list -> list.filterNot { it.id == id } }
        targets.remove(id)
    }

    /** Opens a finished download with the system's app; false if it cannot. */
    suspend fun openDownloaded(id: Int): Boolean {
        val target = targets[id] ?: return false
        return withContext(io) { runCatching { target.open() }.getOrDefault(false) }
    }

    private suspend fun runDownload(id: Int, entry: SftpEntry) {
        var target: DownloadTarget? = null
        var written = 0L
        try {
            target = withContext(io) { store().create(safeFileName(entry.name)) }
            targets[id] = target
            change(id) { it.copy(location = target.location) }
            // The size the file had when the download began: a resume on a file
            // that changed meanwhile would mix two versions.
            var expected = entry.size
            // Reconnections asked for since the download last moved forward.
            var retries = 0
            var failedAt = -1L
            while (true) {
                val sftp = client.first { it != null }!!
                change(id) { it.copy(state = SftpTransfer.State.RUNNING) }
                val outcome = whileAttached(sftp) {
                    if (written > 0 || expected == null) {
                        val now = sftp.stat(entry.path)?.size
                        if (expected == null) expected = now
                        if (written > 0 && now != expected) throw SshSftpFailed("el archivo ha cambiado en el destino")
                    }
                    var lastShown = TimeSource.Monotonic.markNow()
                    sftp.readFrom(entry.path, written) { bytes, length ->
                        withContext(io) {
                            try {
                                target.write(bytes, length)
                            } catch (e: Exception) {
                                throw SaveFailed(e)
                            }
                        }
                        written += length
                        if (lastShown.elapsedNow().inWholeMilliseconds >= PROGRESS_EVERY_MILLIS) {
                            lastShown = TimeSource.Monotonic.markNow()
                            change(id) { it.copy(transferred = written) }
                        }
                    }
                }
                when (outcome) {
                    is Attempt.Done -> break
                    is Attempt.Lost -> change(id) { it.copy(state = SftpTransfer.State.WAITING, transferred = written) }
                    is Attempt.Error -> {
                        val error = outcome.error
                        // The server's answer (no file, no permission) and a local
                        // save that fails are final. Anything else is taken for a
                        // dead connection, unless it keeps failing at the same byte.
                        if (error is SshSftpFailed || error is SaveFailed) throw error
                        if (written > failedAt) {
                            failedAt = written
                            retries = 0
                        }
                        if (++retries > maxRetries) throw error
                        reportLost(sftp)
                        change(id) { it.copy(state = SftpTransfer.State.WAITING, transferred = written) }
                    }
                }
            }
            withContext(io) { target.complete() }
            change(id) { it.copy(state = SftpTransfer.State.DONE, transferred = written, size = it.size ?: written) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                target?.let { t -> withContext(io) { runCatching { t.discard() } } }
            }
            targets.remove(id)
            change(id) { it.copy(state = SftpTransfer.State.CANCELLED, transferred = written) }
            throw e
        } catch (e: Exception) {
            target?.let { t -> withContext(io) { runCatching { t.discard() } } }
            targets.remove(id)
            change(id) { it.copy(state = SftpTransfer.State.FAILED, transferred = written, error = downloadError(e)) }
        } finally {
            jobs.remove(id)
        }
    }

    /** How one attempt on a channel ended. */
    private sealed interface Attempt {
        data object Done : Attempt

        /** The tab detached the channel: wait for the next one. */
        data object Lost : Attempt
        class Error(val error: Exception) : Attempt
    }

    /**
     * Runs [block] on [sftp] and stops it if the tab detaches that channel, so a
     * read stuck on a dead connection does not hold the download.
     */
    private suspend fun whileAttached(sftp: SshSftp, block: suspend () -> Unit): Attempt = coroutineScope {
        val work = async {
            try {
                block()
                Attempt.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (client.value !== sftp) Attempt.Lost else Attempt.Error(e)
            }
        }
        val watch = launch {
            client.first { it !== sftp }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // The channel went away under the read; the browser itself goes on.
            if (client.value === sftp) throw e
            Attempt.Lost
        } finally {
            watch.cancel()
        }
    }

    /** Writing the downloaded bytes on this device failed (full storage, say). */
    private class SaveFailed(cause: Exception) :
        Exception("no se pudo guardar en el dispositivo: " + (cause.message ?: cause::class.simpleName), cause)

    private fun change(id: Int, edit: (SftpTransfer) -> SftpTransfer) {
        _transfers.update { list -> list.map { if (it.id == id) edit(it) else it } }
    }

    /** Stops for good: cancels the downloads (their partial files are deleted). */
    fun close() {
        closed = true
        loadJob?.cancel()
        jobs.values.toList().forEach { it.cancel() }
        onLost = null
        client.value = null
    }

    private fun listingError(e: Exception, path: String?): String {
        val what = e.message?.takeIf { it.isNotBlank() } ?: "error desconocido"
        return if (path == null) "No se pudo abrir la carpeta inicial: $what" else "No se pudo abrir $path: $what"
    }

    private fun downloadError(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: "error desconocido"

    private companion object {
        /** How often the tray's progress moves. */
        const val PROGRESS_EVERY_MILLIS = 150L

        const val MAX_RETRIES = 5
    }
}
