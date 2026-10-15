package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.ssh.SshSftp
import io.github.danielperezmartinez.titanssh.ssh.SshSftpFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** One download or upload of the SFTP browser, for the transfers tray. */
data class SftpTransfer(
    val id: Int,
    val name: String,
    /** The file or folder on the destination: where it comes from, or where an upload goes. */
    val remotePath: String,
    /** Total bytes, when known. */
    val size: Long?,
    val transferred: Long = 0,
    val state: State = State.RUNNING,
    /** Where a download is saved ([DownloadTarget.location]), once the file exists. */
    val location: String? = null,
    /** Why it failed. */
    val error: String? = null,
    val direction: Direction = Direction.DOWNLOAD,
    /** A whole folder downloaded as a folder (ADR-0024). */
    val folder: Boolean = false,
    /** A folder's files, once it has been walked. */
    val files: Int? = null,
    val filesDone: Int = 0,
    /** What a folder download left out: links to folders, devices and files the server refused. */
    val skipped: Int = 0,
    /** An upload's hidden partial file on the destination, renamed to [remotePath] once whole. */
    val partial: String? = null,
) {
    enum class State {
        RUNNING,

        /** The connection dropped: it goes on from [transferred] once the tab reconnects. */
        WAITING,

        /**
         * An upload cancelled without a connection: it waits for one to delete
         * its [partial] file, so nothing is left on the destination.
         */
        CANCELLING,
        DONE,
        FAILED,
        CANCELLED,
    }

    enum class Direction { DOWNLOAD, UPLOAD }

    val finished: Boolean get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
}

/** The outcome of a change to the destination (create, rename, move, delete), shown until dismissed. */
data class SftpMessage(val text: String, val error: Boolean)

/** What is inside some folders about to be deleted, for the second confirmation. */
data class SftpContents(val files: Int, val folders: Int, val bytes: Long) {
    val isEmpty: Boolean get() = files == 0 && folders == 0
}

/** What to do with an upload whose name is already taken in the folder. */
enum class ConflictChoice { REPLACE, KEEP_BOTH, SKIP }

/**
 * An upload waits for the user: [name] is already taken in [folder]. [remaining]
 * uploads of the same batch come after it, so the answer can be kept for them.
 */
class UploadConflict internal constructor(
    val name: String,
    val folder: String,
    val remaining: Int,
    private val reply: CompletableDeferred<Pair<ConflictChoice, Boolean>>,
) {
    /** Answers for this upload, and with [forTheRest] for the rest of the batch. */
    fun answer(choice: ConflictChoice, forTheRest: Boolean = false) {
        reply.complete(choice to forTheRest)
    }
}

/**
 * The state of an SFTP tab (ADR-0021, ADR-0024): the folder on show, the
 * transfers and the changes made to the destination. It outlives the tab's
 * connections. [SessionTab] [attach]es each new SFTP channel and [detach]es it
 * when the connection drops; meanwhile the listing waits, and a transfer or a
 * change in progress pauses ([SftpTransfer.State.WAITING]) and goes on on the
 * next channel: a download from its last written byte, an upload from what
 * the server holds.
 *
 * A request that fails without being the server's answer ([SshSftpFailed]),
 * typically sshj's request timeout on a dead link, tells the tab through the
 * `onLost` of [attach] that the connection is gone: the keepalive takes much
 * longer to notice. The tab reconnects and attaches a new channel.
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
    /** Runs the blocking [DownloadTarget] and [UploadSource] calls; tests pass their own. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * How many reconnections in a row a transfer, a change or a listing asks for
     * without getting anywhere before it gives up: an error that keeps coming
     * back on fresh connections is not the network.
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

    private val _busy = MutableStateFlow<String?>(null)

    /** The change to the destination in progress (`Borrando… 12`), if any. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<SftpMessage?>(null)

    /** How the last change to the destination went. */
    val message: StateFlow<SftpMessage?> = _message.asStateFlow()

    private val _conflict = MutableStateFlow<UploadConflict?>(null)

    /** An upload waiting for the user to say what to do with a name already taken. */
    val conflict: StateFlow<UploadConflict?> = _conflict.asStateFlow()

    /** One batch of uploads asks about its conflicts at a time. */
    private val planning = Mutex()

    /** The user's folder on the server, once known. */
    private var home: String? = null

    private var loadJob: Job? = null
    private val jobs = mutableMapOf<Int, Job>()

    /** Cancelled uploads deleting their partial file ([SftpTransfer.State.CANCELLING]). */
    private val cleanups = mutableMapOf<Int, Job>()

    /** Opens a finished download: its file or its folder. */
    private val openers = mutableMapOf<Int, () -> Boolean>()
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

    // ---- Downloads ----

    /**
     * Downloads [entry] to the device: a file to Downloads, a folder as a folder
     * of Downloads with everything in it (ADR-0024). It runs until it finishes,
     * fails or is cancelled, riding out the tab's reconnections.
     */
    fun download(entry: SftpEntry) {
        if (closed || entry.kind == SftpEntry.Kind.OTHER) return
        val id = nextId++
        if (entry.isDirectory) {
            _transfers.update { it + SftpTransfer(id, entry.name, entry.path, null, folder = true) }
            jobs[id] = scope.launch { runFolderDownload(id, entry) }
        } else {
            _transfers.update { it + SftpTransfer(id, entry.name, entry.path, entry.size) }
            jobs[id] = scope.launch { runDownload(id, entry) }
        }
    }

    /**
     * Stops a transfer in progress and deletes what it had written. An upload
     * without a connection stays [SftpTransfer.State.CANCELLING] until one comes
     * back to delete its partial file on the destination.
     */
    fun cancel(id: Int) {
        val job = jobs.remove(id) ?: return
        job.cancel()
        val partial = _transfers.value.firstOrNull { it.id == id }?.partial ?: return
        change(id) { it.copy(state = SftpTransfer.State.CANCELLING) }
        cleanups[id] = scope.launch {
            // The write lets go of the file first: Windows does not delete an open one.
            job.join()
            discardPartial(id, partial)
        }
    }

    /**
     * The partial files of the uploads not finished yet, cancelling ones
     * included: what closing the browser without a connection would leave on
     * the destination.
     */
    fun partialUploads(): List<String> =
        _transfers.value.filter { it.direction == SftpTransfer.Direction.UPLOAD && !it.finished }.mapNotNull { it.partial }

    /** Removes a finished transfer from the tray (the files stay). */
    fun dismiss(id: Int) {
        if (_transfers.value.firstOrNull { it.id == id }?.finished != true) return
        _transfers.update { list -> list.filterNot { it.id == id } }
        openers.remove(id)
    }

    /** Opens a finished download (its file, or its folder) with the system's app; false if it cannot. */
    suspend fun openDownloaded(id: Int): Boolean {
        val opener = openers[id] ?: return false
        return withContext(io) { runCatching { opener() }.getOrDefault(false) }
    }

    private suspend fun runDownload(id: Int, entry: SftpEntry) {
        var target: DownloadTarget? = null
        try {
            target = withContext(io) { store().create(safeFileName(entry.name)) }
            change(id) { it.copy(location = target.location) }
            val written = fetch(id, entry.path, entry.size, target, base = 0)
            withContext(io) { target.complete() }
            openers[id] = target::open
            // Completing can give the file its final name.
            change(id) { it.copy(state = SftpTransfer.State.DONE, transferred = written, size = it.size ?: written, location = target.location) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                target?.let { t -> withContext(io) { runCatching { t.discard() } } }
            }
            change(id) { it.copy(state = SftpTransfer.State.CANCELLED) }
            throw e
        } catch (e: Exception) {
            target?.let { t -> withContext(io) { runCatching { t.discard() } } }
            change(id) { it.copy(state = SftpTransfer.State.FAILED, error = errorText(e)) }
        } finally {
            jobs.remove(id)
        }
    }

    /**
     * The folder [root] into a new folder of Downloads: first walked whole, to
     * know its files and size, then its files one after the other. A file the
     * server refuses is left out and counted; cancelling or failing deletes
     * everything written.
     */
    private suspend fun runFolderDownload(id: Int, root: SftpEntry) {
        var folder: DownloadFolder? = null
        val written = mutableListOf<DownloadTarget>()
        var target: DownloadTarget? = null
        try {
            val plan = onChannel(
                onAttempt = { change(id) { it.copy(state = SftpTransfer.State.RUNNING) } },
                onWaiting = { change(id) { it.copy(state = SftpTransfer.State.WAITING) } },
            ) { sftp -> walk(sftp, root.path) }
            val total = plan.files.sumOf { it.second.size ?: 0 }
            change(id) { it.copy(size = total, files = plan.files.size, skipped = plan.skipped) }
            val made = withContext(io) {
                store().createFolder(safeFileName(root.name)).also { f -> plan.folders.forEach(f::makeFolder) }
            }
            folder = made
            change(id) { it.copy(location = made.location) }
            var done = 0L
            var skipped = plan.skipped
            plan.files.forEachIndexed { index, (subfolder, file) ->
                val t = withContext(io) { made.create(subfolder, safeFileName(file.name)) }
                target = t
                try {
                    done += fetch(id, file.path, file.size, t, base = done)
                    withContext(io) { t.complete() }
                    written += t
                } catch (e: SshSftpFailed) {
                    // The server refuses this one (no permission, gone meanwhile): leave it out.
                    withContext(io) { runCatching { t.discard() } }
                    skipped++
                }
                target = null
                change(id) { it.copy(filesDone = index + 1, transferred = done, skipped = skipped) }
            }
            openers[id] = made::open
            change(id) { it.copy(state = SftpTransfer.State.DONE, transferred = done) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { discardFolder(target, written, folder) }
            change(id) { it.copy(state = SftpTransfer.State.CANCELLED) }
            throw e
        } catch (e: Exception) {
            discardFolder(target, written, folder)
            change(id) { it.copy(state = SftpTransfer.State.FAILED, error = errorText(e)) }
        } finally {
            jobs.remove(id)
        }
    }

    private suspend fun discardFolder(current: DownloadTarget?, written: List<DownloadTarget>, folder: DownloadFolder?) =
        withContext(io) {
            (listOfNotNull(current) + written).forEach { runCatching { it.discard() } }
            folder?.let { runCatching { it.discard() } }
        }

    /** A folder's content: its files with their subfolder (made safe), its subfolders, and what it leaves out. */
    private class FolderPlan(val files: List<Pair<String, SftpEntry>>, val folders: List<String>, val skipped: Int)

    /**
     * Everything under [path]. Links to files are downloaded as files; links to
     * folders are not followed (they can loop or lead anywhere) and are left
     * out, like devices and sockets.
     */
    private suspend fun walk(sftp: SshSftp, path: String): FolderPlan {
        val files = mutableListOf<Pair<String, SftpEntry>>()
        val folders = mutableListOf<String>()
        var skipped = 0
        val pending = ArrayDeque(listOf(path to ""))
        while (pending.isNotEmpty()) {
            val (dir, local) = pending.removeFirst()
            for (child in sftp.listEntries(dir).sortedBy { it.name }) {
                when (child.kind) {
                    SftpEntry.Kind.DIRECTORY -> {
                        val sub = if (local.isEmpty()) safeFileName(child.name) else local + "/" + safeFileName(child.name)
                        folders += sub
                        pending += child.path to sub
                    }
                    SftpEntry.Kind.FILE, SftpEntry.Kind.LINK -> files += local to child
                    SftpEntry.Kind.LINK_TO_DIRECTORY, SftpEntry.Kind.OTHER -> skipped++
                }
            }
        }
        return FolderPlan(files, folders, skipped)
    }

    /**
     * Reads the remote file [path] into [target], from where it left off on each
     * new channel. [base] is what the transfer had before this file, for the
     * tray. Returns the bytes written.
     */
    private suspend fun fetch(id: Int, path: String, knownSize: Long?, target: DownloadTarget, base: Long): Long {
        var written = 0L
        // The size the file had when the download began: a resume on a file
        // that changed meanwhile would mix two versions.
        var expected = knownSize
        onChannel(
            onAttempt = { change(id) { it.copy(state = SftpTransfer.State.RUNNING) } },
            onWaiting = { change(id) { it.copy(state = SftpTransfer.State.WAITING, transferred = base + written) } },
            progress = { written },
        ) { sftp ->
            if (written > 0 || expected == null) {
                val now = sftp.stat(path)?.size
                if (expected == null) expected = now
                if (written > 0 && now != expected) throw SshSftpFailed("el archivo ha cambiado en el destino")
            }
            var lastShown = TimeSource.Monotonic.markNow()
            sftp.readFrom(path, written) { bytes, length ->
                withContext(io) {
                    try {
                        target.write(bytes, length)
                    } catch (e: Exception) {
                        throw LocalFailed("no se pudo guardar en el dispositivo", e)
                    }
                }
                written += length
                if (lastShown.elapsedNow().inWholeMilliseconds >= PROGRESS_EVERY_MILLIS) {
                    lastShown = TimeSource.Monotonic.markNow()
                    change(id) { it.copy(transferred = base + written) }
                }
            }
        }
        return written
    }

    // ---- Uploads ----

    /**
     * Uploads [sources] to the folder on show (ADR-0024). Each name already
     * taken there is asked about first ([conflict]); then each file goes to a
     * hidden partial file next to its place, renamed to its name once whole, so
     * nobody sees half a file and a resumed upload knows where it was.
     */
    fun upload(sources: List<UploadSource>) {
        val folder = _listing.value.path ?: return
        if (closed || sources.isEmpty()) return
        scope.launch {
            planning.withLock {
                val names = sources.map { remoteName(it.name) }
                val taken = try {
                    onChannel { sftp -> names.map { sftp.stat(SftpPaths.join(folder, it)) != null } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _message.value = SftpMessage("No se pudo subir a $folder: ${errorText(e)}", error = true)
                    return@withLock
                }
                var kept: ConflictChoice? = null
                sources.forEachIndexed { index, source ->
                    val choice = if (!taken[index]) {
                        null
                    } else {
                        kept ?: ask(names[index], folder, remaining = taken.drop(index + 1).count { it }).let { (choice, forTheRest) ->
                            if (forTheRest) kept = choice
                            choice
                        }
                    }
                    if (choice == ConflictChoice.SKIP) return@forEachIndexed
                    startUpload(source, folder, names[index], replace = choice == ConflictChoice.REPLACE)
                }
            }
        }
    }

    private suspend fun ask(name: String, folder: String, remaining: Int): Pair<ConflictChoice, Boolean> {
        val reply = CompletableDeferred<Pair<ConflictChoice, Boolean>>()
        val question = UploadConflict(name, folder, remaining, reply)
        _conflict.value = question
        try {
            return reply.await()
        } finally {
            _conflict.compareAndSet(question, null)
        }
    }

    private fun startUpload(source: UploadSource, folder: String, name: String, replace: Boolean) {
        if (closed) return
        val id = nextId++
        val partial = SftpPaths.join(folder, ".$name$PARTIAL_SUFFIX")
        _transfers.update {
            it + SftpTransfer(
                id, name, SftpPaths.join(folder, name), source.size,
                direction = SftpTransfer.Direction.UPLOAD, partial = partial,
            )
        }
        jobs[id] = scope.launch { runUpload(id, source, folder, name, replace, partial) }
    }

    private suspend fun runUpload(id: Int, source: UploadSource, folder: String, name: String, replace: Boolean, partial: String) {
        var sent = 0L
        try {
            onChannel(
                onAttempt = { change(id) { it.copy(state = SftpTransfer.State.RUNNING) } },
                onWaiting = { change(id) { it.copy(state = SftpTransfer.State.WAITING, transferred = sent) } },
                progress = { sent },
            ) { sftp ->
                // Resume from what the server surely holds: the writes in flight
                // when the connection dropped may have left gaps near its end.
                val offset = if (sent == 0L) {
                    0L
                } else {
                    val held = sftp.stat(partial)?.size ?: 0
                    (minOf(held, sent) - sftp.writeAhead).coerceAtLeast(0)
                }
                sent = offset
                val reader = withContext(io) {
                    try {
                        source.open(offset)
                    } catch (e: Exception) {
                        throw LocalFailed("no se pudo leer ${source.name}", e)
                    }
                }
                try {
                    var lastShown = TimeSource.Monotonic.markNow()
                    sftp.writeFrom(partial, offset) { buffer ->
                        val n = withContext(io) {
                            try {
                                reader.read(buffer)
                            } catch (e: Exception) {
                                throw LocalFailed("no se pudo leer ${source.name}", e)
                            }
                        }
                        if (n > 0) sent += n
                        if (lastShown.elapsedNow().inWholeMilliseconds >= PROGRESS_EVERY_MILLIS) {
                            lastShown = TimeSource.Monotonic.markNow()
                            change(id) { it.copy(transferred = sent) }
                        }
                        n
                    }
                } finally {
                    withContext(NonCancellable + io) { runCatching { reader.close() } }
                }
            }
            val placed = onChannel { sftp -> place(sftp, partial, folder, name, replace, sent) }
            change(id) {
                it.copy(state = SftpTransfer.State.DONE, transferred = sent, size = sent, name = placed.substringAfterLast('/'), remotePath = placed)
            }
            refreshIfShowing(folder)
        } catch (e: CancellationException) {
            // [cancel] and [close] delete the partial file once this lets go of it.
            throw e
        } catch (e: Exception) {
            val left = if (removePartial(partial)) "" else " · queda ${partial.substringAfterLast('/')} en $folder"
            change(id) { it.copy(state = SftpTransfer.State.FAILED, error = errorText(e) + left) }
        } finally {
            jobs.remove(id)
        }
    }

    /**
     * Deletes the [partial] file of cancelled upload [id], waiting for a
     * connection if there is none, and only then calls it cancelled.
     */
    private suspend fun discardPartial(id: Int, partial: String) {
        try {
            onChannel { sftp ->
                try {
                    sftp.remove(partial)
                } catch (e: SshSftpFailed) {
                    // Gone already (it was never written, or an earlier attempt got through).
                    if (sftp.stat(partial) != null) throw e
                }
            }
            change(id) { it.copy(state = SftpTransfer.State.CANCELLED) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            change(id) {
                it.copy(state = SftpTransfer.State.CANCELLED, error = "no se pudo borrar ${partial.substringAfterLast('/')}: ${errorText(e)}")
            }
        } finally {
            cleanups.remove(id)
        }
    }

    /**
     * Gives the whole [partial] file its [name]: in place of a file already
     * there with [replace], else under the first free `name (n)`. Returns where
     * it ended up. Run again after a dropped connection, it notices a rename that
     * went through.
     */
    private suspend fun place(sftp: SshSftp, partial: String, folder: String, name: String, replace: Boolean, size: Long): String {
        val wanted = SftpPaths.join(folder, name)
        if (sftp.stat(partial) == null) {
            if (sftp.stat(wanted)?.size == size) return wanted
            throw SshSftpFailed("el archivo a medio subir ha desaparecido del destino")
        }
        val existing = sftp.stat(wanted)
        val target = when {
            existing == null -> wanted
            replace && !existing.isDirectory -> wanted.also { sftp.remove(it) }
            else -> freeName(sftp, folder, name)
        }
        sftp.rename(partial, target)
        return target
    }

    private suspend fun freeName(sftp: SshSftp, folder: String, name: String): String {
        var n = 1
        while (true) {
            val candidate = SftpPaths.join(folder, numberedFileName(name, n))
            if (sftp.stat(candidate) == null) return candidate
            n++
        }
    }

    /**
     * Deletes the partial file of a failed upload if the connection allows it
     * now; false if it stays on the destination.
     */
    private suspend fun removePartial(partial: String): Boolean {
        val sftp = client.value ?: return false
        return withContext(NonCancellable) {
            withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) {
                runCatching { sftp.remove(partial) }.isSuccess || runCatching { sftp.stat(partial) == null }.getOrDefault(false)
            } ?: false
        }
    }

    // ---- Changes to the destination ----

    /** Creates the folder [name] in the folder on show. */
    fun createFolder(name: String) {
        val folder = _listing.value.path ?: return
        val clean = name.trim()
        SftpPaths.nameProblem(clean)?.let { return showError(it) }
        val path = SftpPaths.join(folder, clean)
        operate("Creando $clean…", folder) { sftp, retry ->
            // A retry after a lost answer finds the folder made.
            if (retry && sftp.stat(path)?.isDirectory == true) return@operate null
            if (sftp.stat(path) != null) throw SshSftpFailed("ya existe $clean")
            sftp.makeDirectory(path)
            null
        }
    }

    /** Gives [entry] the name [newName] in its own folder. */
    fun rename(entry: SftpEntry, newName: String) {
        val clean = newName.trim()
        if (clean == entry.name) return
        SftpPaths.nameProblem(clean)?.let { return showError(it) }
        val folder = SftpPaths.parent(entry.path) ?: return
        val target = SftpPaths.join(folder, clean)
        operate("Renombrando ${entry.name}…", folder) { sftp, retry ->
            if (retry && !exists(sftp, entry) && sftp.stat(target) != null) return@operate null
            if (sftp.stat(target) != null && !sameIgnoringCase(target, entry.path)) throw SshSftpFailed("ya existe $clean")
            sftp.rename(entry.path, target)
            null
        }
    }

    /**
     * Moves [entries] into [destination]. One whose name is taken there, or a
     * folder asked to go inside itself, stays where it is; the message says which.
     */
    fun move(entries: List<SftpEntry>, destination: String) {
        if (entries.isEmpty()) return
        operate(if (entries.size == 1) "Moviendo ${entries[0].name}…" else "Moviendo ${entries.size} elementos…", destination) { sftp, retry ->
            var moved = 0
            val problems = mutableListOf<String>()
            for (entry in entries) {
                val source = entry.path.trimEnd('/')
                val target = SftpPaths.join(destination, entry.name)
                when {
                    SftpPaths.parent(source)?.let { samePath(it, destination) } == true -> problems += "${entry.name} ya está en esta carpeta"
                    entry.isDirectory && (samePath(destination, source) || destination.startsWith("$source/")) ->
                        problems += "${entry.name} no puede ir dentro de sí misma"
                    retry && !exists(sftp, entry) && sftp.stat(target) != null -> moved++
                    sftp.stat(target) != null -> problems += "ya existe ${entry.name} en el destino"
                    else -> {
                        sftp.rename(source, target)
                        moved++
                    }
                }
            }
            summary(if (moved == 1) "Movido 1 elemento" else "Movidos $moved elementos", problems)
        }
    }

    /**
     * What deleting [entries] would take with it: the files and folders inside
     * the folders among them. Links are not followed: deleting one deletes the
     * link only.
     */
    suspend fun contentsOf(entries: List<SftpEntry>): SftpContents = onChannel { sftp ->
        var files = 0
        var folders = 0
        var bytes = 0L
        val pending = ArrayDeque(entries.filter { it.kind == SftpEntry.Kind.DIRECTORY }.map { it.path })
        while (pending.isNotEmpty()) {
            for (child in sftp.listEntries(pending.removeFirst())) {
                if (child.kind == SftpEntry.Kind.DIRECTORY) {
                    folders++
                    pending += child.path
                } else {
                    files++
                    if (child.kind == SftpEntry.Kind.FILE) bytes += child.size ?: 0
                }
            }
        }
        SftpContents(files, folders, bytes)
    }

    /**
     * Deletes [entries] for good, folders with everything in them. The view
     * asks first, twice for a folder with content (ADR-0024).
     */
    fun delete(entries: List<SftpEntry>) {
        if (entries.isEmpty()) return
        val folder = SftpPaths.parent(entries[0].path) ?: return
        val label = if (entries.size == 1) "Borrando ${entries[0].name}…" else "Borrando ${entries.size} elementos…"
        operate(label, folder) { sftp, _ ->
            var count = 0
            // Listed again on every attempt: what an earlier one deleted is gone.
            val present = sftp.listEntries(folder).map { it.path }.toSet()
            for (entry in entries) {
                if (entry.path !in present) continue
                deleteTree(sftp, entry) { count++; _busy.value = "$label $count" }
            }
            summary(if (entries.size == 1) "Borrado ${entries[0].name}" else "Borrados ${entries.size} elementos", emptyList())
        }
    }

    /** Deletes [entry], a folder after its content; a link is deleted, never followed. */
    private suspend fun deleteTree(sftp: SshSftp, entry: SftpEntry, onDeleted: () -> Unit) {
        if (entry.kind == SftpEntry.Kind.DIRECTORY) {
            for (child in sftp.listEntries(entry.path)) deleteTree(sftp, child, onDeleted)
            sftp.removeDirectory(entry.path)
        } else {
            sftp.remove(entry.path)
        }
        onDeleted()
    }

    /** Whether [entry] is still in its folder (a link counts even if what it points to is gone). */
    private suspend fun exists(sftp: SshSftp, entry: SftpEntry): Boolean {
        val folder = SftpPaths.parent(entry.path) ?: return true
        return sftp.listEntries(folder).any { it.path == entry.path }
    }

    /** Windows servers ignore case: renaming `a` to `A` is not a clash with itself. */
    private fun sameIgnoringCase(a: String, b: String) = a.equals(b, ignoreCase = true)

    private fun summary(done: String, problems: List<String>) =
        SftpMessage((listOf(done) + problems).joinToString(" · "), error = problems.isNotEmpty())

    private fun samePath(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')

    /** Dismisses [message]. */
    fun clearMessage() {
        _message.value = null
    }

    /** Shows [text] as an error in [message], e.g. a problem the view found before asking the browser. */
    fun showError(text: String) {
        _message.value = SftpMessage(text, error = true)
    }

    /**
     * Runs a change to the destination on a channel, riding out reconnections
     * ([onChannel]; `retry` tells [block] an earlier attempt may have got
     * through), then lists [folder] again if it is on show. [block] returns the
     * message to show, if any.
     */
    private fun operate(label: String, folder: String, block: suspend (SshSftp, Boolean) -> SftpMessage?) {
        if (closed) return
        _message.value = null
        scope.launch {
            _busy.value = label
            try {
                var attempts = 0
                onChannel { sftp -> block(sftp, attempts++ > 0) }?.let { _message.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = SftpMessage(errorText(e).replaceFirstChar { it.uppercase() }, error = true)
            } finally {
                _busy.value = null
                refreshIfShowing(folder)
            }
        }
    }

    private fun refreshIfShowing(folder: String) {
        if (_listing.value.path == folder) refresh()
    }

    // ---- Channel handling ----

    /** How one attempt on a channel ended. */
    private sealed interface Attempt<out T> {
        class Done<T>(val value: T) : Attempt<T>

        /** The tab detached the channel: wait for the next one. */
        data object Lost : Attempt<Nothing>
        class Error(val error: Exception) : Attempt<Nothing>
    }

    /**
     * Runs [block] on the channel until it gets through, riding out the tab's
     * reconnections: on each new channel [onAttempt], while waiting for one
     * [onWaiting]. The server's answer ([SshSftpFailed]) and a failure on this
     * device ([LocalFailed]) are final. Anything else is taken for a dead
     * connection, unless it keeps failing without [progress] moving.
     */
    private suspend fun <T> onChannel(
        onAttempt: () -> Unit = {},
        onWaiting: () -> Unit = {},
        progress: () -> Long = { 0 },
        block: suspend (SshSftp) -> T,
    ): T {
        var retries = 0
        var failedAt = -1L
        while (true) {
            val sftp = client.first { it != null }!!
            onAttempt()
            when (val outcome = whileAttached(sftp) { block(sftp) }) {
                is Attempt.Done -> return outcome.value
                is Attempt.Lost -> onWaiting()
                is Attempt.Error -> {
                    val error = outcome.error
                    if (error is SshSftpFailed || error is LocalFailed) throw error
                    val at = progress()
                    if (at > failedAt) {
                        failedAt = at
                        retries = 0
                    }
                    if (++retries > maxRetries) throw error
                    reportLost(sftp)
                    onWaiting()
                }
            }
        }
    }

    /**
     * Runs [block] on [sftp] and stops it if the tab detaches that channel, so a
     * request stuck on a dead connection does not hold the work.
     */
    private suspend fun <T> whileAttached(sftp: SshSftp, block: suspend () -> T): Attempt<T> = coroutineScope {
        val work = async {
            try {
                Attempt.Done(block())
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
            // The channel went away under the request; the browser itself goes on.
            if (client.value === sftp) throw e
            Attempt.Lost
        } finally {
            watch.cancel()
        }
    }

    /** Reading or writing the file on this device failed (full storage, say). */
    private class LocalFailed(what: String, cause: Exception) :
        Exception(what + ": " + (cause.message ?: cause::class.simpleName), cause)

    private fun change(id: Int, edit: (SftpTransfer) -> SftpTransfer) {
        _transfers.update { list -> list.map { if (it.id == id) edit(it) else it } }
    }

    /**
     * Stops for good: cancels the transfers and the changes. Downloads delete
     * their partial files on this device; the uploads' partial files on the
     * destination are deleted before letting go of the channel, if there is one
     * ([partialUploads] says what stays otherwise).
     */
    suspend fun close() {
        if (closed) return
        closed = true
        loadJob?.cancel()
        _conflict.value?.answer(ConflictChoice.SKIP, forTheRest = true)
        val partials = partialUploads()
        val running = jobs.values.toList() + cleanups.values.toList()
        running.forEach { it.cancel() }
        withContext(NonCancellable) {
            // The writes stop and close their files before these are deleted.
            withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { running.forEach { it.join() } }
            val sftp = client.value
            if (sftp != null && partials.isNotEmpty()) {
                withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { partials.forEach { runCatching { sftp.remove(it) } } }
            }
        }
        onLost = null
        client.value = null
    }

    private fun listingError(e: Exception, path: String?): String {
        val what = e.message?.takeIf { it.isNotBlank() } ?: "error desconocido"
        return if (path == null) "No se pudo abrir la carpeta inicial: $what" else "No se pudo abrir $path: $what"
    }

    private fun errorText(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: "error desconocido"

    /** A local file name as a name on the destination: no path separators. */
    private fun remoteName(name: String): String = safeFileName(name)

    private companion object {
        /** How often the tray's progress moves. */
        const val PROGRESS_EVERY_MILLIS = 150L

        const val MAX_RETRIES = 5

        /** An upload in progress is `.<name>.titan-part` next to its place. */
        const val PARTIAL_SUFFIX = ".titan-part"

        /** How long a cancelled upload waits to delete its partial file. */
        const val CLEANUP_TIMEOUT_MILLIS = 5_000L
    }
}
