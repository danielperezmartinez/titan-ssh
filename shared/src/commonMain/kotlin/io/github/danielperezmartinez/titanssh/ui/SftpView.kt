package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import io.github.danielperezmartinez.titanssh.formatLocalDateTime
import io.github.danielperezmartinez.titanssh.sftp.ConflictChoice
import io.github.danielperezmartinez.titanssh.sftp.SftpBrowser
import io.github.danielperezmartinez.titanssh.sftp.SftpContents
import io.github.danielperezmartinez.titanssh.sftp.SftpPaths
import io.github.danielperezmartinez.titanssh.sftp.SftpTransfer
import io.github.danielperezmartinez.titanssh.sftp.UploadConflict
import io.github.danielperezmartinez.titanssh.sftp.rememberUploadPicker
import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TabStatus
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Where a deletion asked from the view stands (ADR-0024). */
private sealed interface DeleteStep {
    val entries: List<SftpEntry>

    /** The quick question. */
    class Ask(override val entries: List<SftpEntry>) : DeleteStep

    /** Yes to the quick question: looking at what the folders hold. */
    class Counting(override val entries: List<SftpEntry>) : DeleteStep

    /** The folders hold something: the serious warning, a second yes. */
    class Warn(override val entries: List<SftpEntry>, val contents: SftpContents) : DeleteStep
}

/**
 * An SFTP tab (ADR-0021, ADR-0024): the destination's folders. Tapping a
 * folder opens it; tapping a file unfolds its details and its actions
 * (download, rename, delete). Holding an entry starts the selection mode, where
 * taps mark entries and the bar at the bottom downloads, moves or deletes them
 * all at once; a folder downloads as a folder. Outside it, the bar offers a new
 * folder and uploading files from the device. Deleting asks first, and twice
 * for folders with something in them. The path on top can be walked back by
 * its parts or typed; the transfers tray at the bottom shows their progress,
 * and a transfer caught by a dropped connection waits and goes on by itself.
 */
@Composable
fun SftpView(
    tab: SessionTab,
    browser: SftpBrowser,
    modifier: Modifier = Modifier,
    /** Shown full screen: the strip offers `[v]` to leave it. */
    onExitFullscreen: (() -> Unit)? = null,
) {
    val status by tab.status.collectAsState()
    val listing by browser.listing.collectAsState()
    val transfers by browser.transfers.collectAsState()
    val busy by browser.busy.collectAsState()
    val message by browser.message.collectAsState()
    val conflict by browser.conflict.collectAsState()
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val changedHostKey by tab.changedHostKey.collectAsState()
    val scope = rememberCoroutineScope()

    var showHidden by remember(tab.id) { mutableStateOf(false) }
    // The file whose details and actions are unfolded; one at a time.
    var openFile by remember(tab.id) { mutableStateOf<String?>(null) }
    var editingPath by remember(tab.id) { mutableStateOf<String?>(null) }
    var causeOpen by remember(tab.id) { mutableStateOf(false) }
    // The selection mode: the paths marked in the folder on show; empty is off.
    var selected by remember(tab.id) { mutableStateOf(emptySet<String>()) }
    // Entries on their way to another folder: the user walks to it and moves them there.
    var moving by remember(tab.id) { mutableStateOf<List<SftpEntry>?>(null) }
    var deleting by remember(tab.id) { mutableStateOf<DeleteStep?>(null) }
    // The entry being renamed with its new name, from a file's actions or the selection.
    var renaming by remember(tab.id) { mutableStateOf<Pair<SftpEntry, String>?>(null) }
    var newFolder by remember(tab.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(listing.path) {
        openFile = null
        selected = emptySet()
        deleting = null
        renaming = null
        newFolder = null
    }
    val pickFiles = rememberUploadPicker { browser.upload(it) }

    val entries = remember(listing.entries, showHidden) { SftpPaths.sorted(listing.entries, showHidden) }
    val selectedEntries = entries.filter { it.path in selected }
    val selecting = selected.isNotEmpty()

    fun toggle(entry: SftpEntry) {
        selected = if (entry.path in selected) selected - entry.path else selected + entry.path
    }

    fun askDelete(targets: List<SftpEntry>) {
        renaming = null
        deleting = DeleteStep.Ask(targets)
    }

    fun confirmDelete(step: DeleteStep) {
        val targets = step.entries
        // A link to a folder is deleted as a link: only real folders can hold anything.
        if (targets.none { it.kind == SftpEntry.Kind.DIRECTORY }) {
            browser.delete(targets)
            deleting = null
            selected = emptySet()
            return
        }
        deleting = DeleteStep.Counting(targets)
        scope.launch {
            val contents = try {
                browser.contentsOf(targets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = null
                browser.showError("No se pudo ver qué contiene: ${e.message ?: "error desconocido"}")
                return@launch
            }
            if (deleting !is DeleteStep.Counting) return@launch
            if (contents.isEmpty) {
                browser.delete(targets)
                deleting = null
                selected = emptySet()
            } else {
                deleting = DeleteStep.Warn(targets, contents)
            }
        }
    }

    Column(modifier.fillMaxSize().background(TitanColors.Canvas)) {
        pendingHostKey?.let { pending ->
            HostKeyPromptBar(
                fingerprint = pending.info.fingerprintSha256,
                keyType = pending.info.keyType,
                host = pending.info.host,
                onAccept = { pending.accept() },
                onReject = { pending.reject() },
            )
            Hairline()
        }
        changedHostKey?.let { changed ->
            ChangedHostKeyBar(changed, onReplace = { scope.launch { tab.replaceHostKey() } })
            Hairline()
        }
        val cause = status.cause
        SftpStatusStrip(
            status,
            onReconnect = { tab.reconnectNow() },
            causeOpen = causeOpen && cause != null,
            onToggleCause = if (cause != null) ({ causeOpen = !causeOpen }) else null,
            onExitFullscreen = onExitFullscreen,
        )
        Hairline()
        if (causeOpen && cause != null) {
            StatusDetailPanel(cause)
            Hairline()
        }

        val path = listing.path
        val editing = editingPath
        when {
            selecting -> SelectionBar(
                count = selected.size,
                all = entries.isNotEmpty() && selectedEntries.size == entries.size,
                onAll = { selected = entries.map { it.path }.toSet() },
                onNone = { selected = emptySet() },
            )
            editing != null -> Row(
                Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TitanTextField("", editing, { editingPath = it }, placeholder = "/ruta/a/la/carpeta")
                }
                GlyphButton("[>]", onClick = {
                    editing.trim().takeIf { it.isNotEmpty() }?.let(browser::open)
                    editingPath = null
                }, color = TitanColors.Accent)
                GlyphButton("[x]", onClick = { editingPath = null })
            }
            else -> PathBar(
                path = path,
                onOpen = browser::open,
                onUp = browser::up,
                onHome = browser::goHome,
                onEdit = { editingPath = path ?: "" },
                showHidden = showHidden,
                onToggleHidden = { showHidden = !showHidden },
                onRefresh = browser::refresh,
            )
        }
        Hairline()
        newFolder?.let { name ->
            NameRow(
                value = name,
                placeholder = "nombre de la carpeta",
                confirm = "[+]",
                onChange = { newFolder = it },
                onDone = {
                    browser.createFolder(name)
                    newFolder = null
                },
                onCancel = { newFolder = null },
            )
            Hairline()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                path == null && listing.error == null -> EmptyState(
                    if (status.phase == TabPhase.FAILED || status.phase == TabPhase.DISCONNECTED) {
                        status.detail ?: "Sin conexión con el destino."
                    } else {
                        "Conectando…"
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = TitanDimens.SpaceSm)) {
                    listing.error?.let { error ->
                        item(key = "error") {
                            Caption(error, Modifier.padding(TitanDimens.SpaceSm), color = TitanColors.Danger)
                        }
                    }
                    listing.notice?.let { notice ->
                        item(key = "notice") {
                            Caption(notice, Modifier.padding(TitanDimens.SpaceSm), color = TitanColors.Warning)
                        }
                    }
                    if (listing.loading) {
                        item(key = "loading") { Caption("Cargando…", Modifier.padding(TitanDimens.SpaceSm)) }
                    }
                    if (!listing.loading && listing.error == null && entries.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(if (listing.entries.isEmpty()) "Carpeta vacía." else "Solo hay archivos ocultos ([.] para verlos).")
                        }
                    }
                    items(entries, key = { it.path }) { entry ->
                        val renamingThis = renaming?.takeIf { !selecting && it.first.path == entry.path }
                        val deletingThis = (deleting as? DeleteStep.Ask)?.takeIf { !selecting && it.entries.singleOrNull()?.path == entry.path }
                        EntryRow(
                            entry,
                            open = openFile == entry.path && !selecting,
                            check = if (selecting) entry.path in selected else null,
                            onClick = {
                                when {
                                    selecting -> toggle(entry)
                                    entry.isDirectory -> browser.open(entry.path)
                                    entry.kind == SftpEntry.Kind.OTHER || moving != null -> Unit
                                    else -> {
                                        openFile = if (openFile == entry.path) null else entry.path
                                        renaming = null
                                        deleting = null
                                    }
                                }
                            },
                            // Moving walks the folders: no selection meanwhile.
                            onLongClick = if (moving != null) null else ({
                                if (!selecting) {
                                    openFile = null
                                    renaming = null
                                    deleting = null
                                }
                                toggle(entry)
                            }),
                            actions = {
                                ListRow(marker = "[v]", title = "Descargar", subtitle = "a Descargas", onClick = {
                                    browser.download(entry)
                                    openFile = null
                                })
                                if (renamingThis != null) {
                                    NameRow(
                                        value = renamingThis.second,
                                        placeholder = "nombre nuevo",
                                        confirm = "[>]",
                                        onChange = { renaming = entry to it },
                                        onDone = {
                                            browser.rename(entry, renamingThis.second)
                                            renaming = null
                                            openFile = null
                                        },
                                        onCancel = { renaming = null },
                                    )
                                } else {
                                    ListRow(marker = "[~]", title = "Renombrar", onClick = {
                                        deleting = null
                                        renaming = entry to entry.name
                                    })
                                }
                                if (deletingThis != null) {
                                    ConfirmRow(
                                        question = "¿Borrar ${entry.name}?",
                                        subtitle = if (entry.kind == SftpEntry.Kind.LINK) "se borra el enlace, no el archivo al que apunta" else "no se puede deshacer",
                                        confirmLabel = "[x] Sí",
                                        marker = "[x]",
                                        markerColor = TitanColors.Danger,
                                        onConfirm = {
                                            browser.delete(listOf(entry))
                                            deleting = null
                                            openFile = null
                                        },
                                        onCancel = { deleting = null },
                                    )
                                } else {
                                    ListRow(marker = "[x]", title = "Borrar", markerColor = TitanColors.Danger, onClick = { askDelete(listOf(entry)) })
                                }
                            },
                        )
                        Hairline()
                    }
                }
            }
        }

        val note = busy?.let { it to false } ?: message?.let { it.text to it.error }
        if (note != null) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Caption(
                    note.first,
                    Modifier.weight(1f).padding(vertical = TitanDimens.SpaceXs),
                    color = if (note.second) TitanColors.Danger else TitanColors.Mute,
                )
                if (busy == null) GlyphButton("[-]", onClick = browser::clearMessage)
            }
        }

        conflict?.let { question ->
            Hairline()
            ConflictPanel(question)
        }

        val step = deleting
        val renamingSelected = renaming?.takeIf { selecting }
        Hairline()
        when {
            step != null && (selecting || step !is DeleteStep.Ask) -> DeletePanel(
                step,
                onConfirm = { confirmDelete(step) },
                onConfirmAll = {
                    browser.delete(step.entries)
                    deleting = null
                    selected = emptySet()
                },
                onCancel = { deleting = null },
            )
            renamingSelected != null -> NameRow(
                value = renamingSelected.second,
                placeholder = "nombre nuevo",
                confirm = "[>]",
                onChange = { renaming = renamingSelected.first to it },
                onDone = {
                    browser.rename(renamingSelected.first, renamingSelected.second)
                    renaming = null
                    selected = emptySet()
                },
                onCancel = { renaming = null },
            )
            selecting -> ActionsBar {
                TitanButton("[v] Descargar", onClick = {
                    selectedEntries.forEach(browser::download)
                    selected = emptySet()
                })
                TitanButton("[>] Mover", onClick = {
                    moving = selectedEntries
                    selected = emptySet()
                })
                if (selectedEntries.size == 1) {
                    TitanButton("[~] Renombrar", onClick = { renaming = selectedEntries[0] to selectedEntries[0].name })
                }
                TitanButton("[x] Borrar", onClick = { askDelete(selectedEntries) }, kind = ButtonKind.DANGER)
            }
            moving != null -> {
                val items = moving.orEmpty()
                Column(Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceXs)) {
                    Caption(
                        (if (items.size == 1) "Mover ${items[0].name}" else "Mover ${items.size} elementos") +
                            ": abre la carpeta de destino y pulsa «Mover aquí».",
                    )
                    ActionsBar(padded = false) {
                        TitanButton("[<] Cancelar", onClick = { moving = null })
                        TitanButton("[>] Mover aquí", onClick = {
                            path?.let { browser.move(items, it) }
                            moving = null
                        }, kind = ButtonKind.PRIMARY, enabled = path != null)
                    }
                }
            }
            else -> ActionsBar {
                TitanButton("[+] Carpeta", onClick = { newFolder = "" }, enabled = path != null)
                TitanButton("[^] Subir archivos", onClick = pickFiles, enabled = path != null)
            }
        }

        if (transfers.isNotEmpty()) {
            Hairline()
            TransfersTray(
                transfers,
                connected = status.phase == TabPhase.CONNECTED,
                // The tab gave up reconnecting: a pending deletion waits for the user.
                gaveUp = status.phase == TabPhase.DISCONNECTED || status.phase == TabPhase.FAILED,
                onCancel = browser::cancel,
                onDismiss = browser::dismiss,
                onOpen = { id -> scope.launch { browser.openDownloaded(id) } },
            )
        }
    }
}

/** The folder's parts, each one a tap away, and the browser's actions. */
@Composable
private fun PathBar(
    path: String?,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onEdit: () -> Unit,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton("[^]", onClick = onUp, enabled = path != null && SftpPaths.parent(path) != null, color = TitanColors.Body)
        GlyphButton("[~]", onClick = onHome, enabled = path != null, color = TitanColors.Body)
        val scroll = rememberScrollState()
        // Keep the deepest part in sight.
        LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = path?.let(SftpPaths::crumbs).orEmpty()
            crumbs.forEachIndexed { index, (name, target) ->
                val last = index == crumbs.lastIndex
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (last) TitanColors.Ink else TitanColors.Accent,
                    modifier = Modifier
                        .clickable(enabled = !last) { onOpen(target) }
                        .padding(horizontal = 2.dp, vertical = TitanDimens.SpaceXs),
                )
                if (index > 0 && !last) {
                    Text("/", style = MaterialTheme.typography.bodyMedium, color = TitanColors.Stone)
                }
            }
        }
        GlyphButton("[/]", onClick = onEdit, color = TitanColors.Body)
        GlyphButton("[.]", onClick = onToggleHidden, color = if (showHidden) TitanColors.Accent else TitanColors.Mute)
        GlyphButton("[r]", onClick = onRefresh, enabled = path != null, color = TitanColors.Body)
    }
}

/** In place of the path while selecting: how many are marked, and all or none. */
@Composable
private fun SelectionBar(count: Int, all: Boolean, onAll: () -> Unit, onNone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(end = TitanDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton("[x]", onClick = onNone, color = TitanColors.Body)
        Text(
            if (count == 1) "1 seleccionado" else "$count seleccionados",
            style = MaterialTheme.typography.bodyMedium,
            color = TitanColors.Ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (all) "ninguno" else "todo",
            style = MaterialTheme.typography.bodyMedium,
            color = TitanColors.Accent,
            modifier = Modifier
                .clickable(onClick = if (all) onNone else onAll)
                .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceSm),
        )
    }
}

/** A row of buttons that wraps onto new lines on a narrow screen. */
@Composable
private fun ActionsBar(padded: Boolean = true, content: @Composable () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
    ) {
        content()
    }
}

/** A name being typed (a new folder, a rename), with confirm and cancel. */
@Composable
private fun NameRow(
    value: String,
    placeholder: String,
    confirm: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            TitanTextField("", value, onChange, placeholder = placeholder, focusRequester = focus, onDone = onDone)
        }
        GlyphButton(confirm, onClick = onDone, color = TitanColors.Accent, enabled = value.isNotBlank())
        GlyphButton("[x]", onClick = onCancel)
    }
}

/**
 * Deleting what the selection holds: the quick question, then, for folders
 * with something in them, the serious warning that says what goes with them.
 */
@Composable
private fun DeletePanel(step: DeleteStep, onConfirm: () -> Unit, onConfirmAll: () -> Unit, onCancel: () -> Unit) {
    val entries = step.entries
    val what = if (entries.size == 1) entries[0].name else "${entries.size} elementos"
    when (step) {
        is DeleteStep.Ask -> ConfirmRow(
            question = "¿Borrar $what?",
            subtitle = deleteSubtitle(entries),
            confirmLabel = "[x] Sí",
            marker = "[x]",
            markerColor = TitanColors.Danger,
            onConfirm = onConfirm,
            onCancel = onCancel,
            modifier = Modifier.padding(horizontal = TitanDimens.SpaceSm),
        )
        is DeleteStep.Counting -> Row(
            Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption("Mirando qué contiene…", Modifier.weight(1f))
            GlyphButton("[x]", onClick = onCancel)
        }
        is DeleteStep.Warn -> Column(
            Modifier
                .fillMaxWidth()
                .padding(TitanDimens.SpaceSm)
                .border(TitanDimens.Hairline, TitanColors.Danger)
                .background(TitanColors.Surface)
                .padding(TitanDimens.SpaceMd),
        ) {
            Text("[!] Borrado definitivo", style = MaterialTheme.typography.bodyLarge, color = TitanColors.Danger)
            Spacer(Modifier.height(TitanDimens.SpaceXs))
            Text(warning(entries, step.contents), style = MaterialTheme.typography.bodyMedium, color = TitanColors.Body)
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TitanButton("[<] No", onClick = onCancel)
                Spacer(Modifier.width(TitanDimens.SpaceSm))
                TitanButton("[x] Sí, borrar todo", onClick = onConfirmAll, kind = ButtonKind.DANGER)
            }
        }
    }
}

private fun deleteSubtitle(entries: List<SftpEntry>): String {
    val folders = entries.count { it.kind == SftpEntry.Kind.DIRECTORY }
    val links = entries.count { it.kind == SftpEntry.Kind.LINK || it.kind == SftpEntry.Kind.LINK_TO_DIRECTORY }
    return listOfNotNull(
        when {
            folders == 0 -> null
            entries.size == 1 -> "carpeta con lo que contenga"
            else -> "incluye $folders carpeta" + (if (folders == 1) "" else "s") + " con lo que contengan"
        },
        when {
            links == 0 -> null
            entries.size == 1 -> "se borra el enlace, no a lo que apunta"
            else -> "los enlaces se borran sin tocar a lo que apuntan"
        },
    ).joinToString("  ·  ").ifEmpty { "no se puede deshacer" }
}

/** The second confirmation: what will be gone, in numbers. */
private fun warning(entries: List<SftpEntry>, contents: SftpContents): String {
    val folders = entries.filter { it.kind == SftpEntry.Kind.DIRECTORY }
    val which = if (folders.size == 1) "La carpeta ${folders[0].name} no está vacía" else "Las ${folders.size} carpetas no están vacías"
    val inside = listOfNotNull(
        contents.files.takeIf { it > 0 }?.let { if (it == 1) "1 archivo" else "$it archivos" },
        contents.folders.takeIf { it > 0 }?.let { if (it == 1) "1 subcarpeta" else "$it subcarpetas" },
    ).joinToString(" y ")
    val size = if (contents.bytes > 0) " (${SftpPaths.formatSize(contents.bytes)})" else ""
    val others = entries.size - folders.size
    val also = when (others) {
        0 -> ""
        1 -> " También se borra el otro elemento seleccionado."
        else -> " También se borran los otros $others elementos seleccionados."
    }
    return "$which: dentro hay $inside$size. Si sigues, se borra todo, con lo que contiene.$also " +
        "Se borra del destino para siempre: SFTP no tiene papelera y no se puede deshacer."
}

/** An upload whose name is taken: replace, keep both or skip, maybe for the rest too. */
@Composable
private fun ConflictPanel(question: UploadConflict) {
    var forTheRest by remember(question) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm)) {
        Text("Ya existe ${question.name} en esta carpeta.", style = MaterialTheme.typography.bodyMedium, color = TitanColors.Ink)
        if (question.remaining > 0) {
            TitanCheck(
                if (question.remaining == 1) "Hacer lo mismo con el otro que también existe" else "Hacer lo mismo con los ${question.remaining} siguientes que también existen",
                forTheRest,
            ) { forTheRest = it }
        }
        ActionsBar(padded = false) {
            TitanButton("[x] Reemplazar", onClick = { question.answer(ConflictChoice.REPLACE, forTheRest) }, kind = ButtonKind.DANGER)
            TitanButton("[+] Conservar ambos", onClick = { question.answer(ConflictChoice.KEEP_BOTH, forTheRest) }, kind = ButtonKind.PRIMARY)
            TitanButton("[-] Omitir", onClick = { question.answer(ConflictChoice.SKIP, forTheRest) })
        }
    }
}

@Composable
private fun EntryRow(
    entry: SftpEntry,
    open: Boolean,
    /** In the selection mode, whether it is marked; null outside it. */
    check: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    val (marker, color) = when {
        check == true -> "[x]" to TitanColors.Accent
        check == false -> "[ ]" to TitanColors.Mute
        else -> when (entry.kind) {
            SftpEntry.Kind.DIRECTORY -> "[d]" to TitanColors.Accent
            SftpEntry.Kind.LINK_TO_DIRECTORY -> "[@]" to TitanColors.Accent
            SftpEntry.Kind.FILE -> "[-]" to TitanColors.Body
            SftpEntry.Kind.LINK -> "[@]" to TitanColors.Body
            SftpEntry.Kind.OTHER -> "[?]" to TitanColors.Stone
        }
    }
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    val details = listOfNotNull(
        entry.size?.takeUnless { entry.isDirectory }?.let(SftpPaths::formatSize),
        entry.modifiedEpochSeconds?.let { formatLocalDateTime(it * 1000, now) },
    ).joinToString("  ·  ").ifEmpty { null }
    ListRow(
        title = if (entry.isDirectory) entry.name + "/" else entry.name,
        subtitle = details,
        marker = marker,
        markerColor = color,
        onClick = onClick,
        onLongClick = onLongClick,
        expanded = open,
        expandedContent = {
            val facts = listOfNotNull(
                entry.size?.let { "$it bytes" },
                entry.permissions?.let(SftpPaths::formatPermissions),
                "enlace".takeIf { entry.kind == SftpEntry.Kind.LINK },
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) Caption(facts, Modifier.padding(vertical = TitanDimens.SpaceXs))
            actions()
        },
    )
}

/** The transfers of this tab, newest last. */
@Composable
private fun TransfersTray(
    transfers: List<SftpTransfer>,
    connected: Boolean,
    gaveUp: Boolean,
    onCancel: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    // A new transfer goes last: bring it into the tray's few rows.
    LaunchedEffect(transfers.lastOrNull()?.id) {
        if (transfers.isNotEmpty()) state.animateScrollToItem(transfers.lastIndex)
    }
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .background(TitanColors.Surface)
            .padding(horizontal = TitanDimens.SpaceSm),
    ) {
        items(transfers, key = { it.id }) { t ->
            val upload = t.direction == SftpTransfer.Direction.UPLOAD
            val (marker, color) = when (t.state) {
                SftpTransfer.State.RUNNING -> (if (upload) "[^]" else "[v]") to TitanColors.Accent
                SftpTransfer.State.WAITING, SftpTransfer.State.CANCELLING -> "[-]" to TitanColors.Warning
                SftpTransfer.State.DONE -> "[+]" to TitanColors.Success
                SftpTransfer.State.FAILED -> "[x]" to TitanColors.Danger
                SftpTransfer.State.CANCELLED -> "[x]" to TitanColors.Mute
            }
            val openable = t.state == SftpTransfer.State.DONE && !upload
            ListRow(
                title = if (t.folder) t.name + "/" else t.name,
                subtitle = transferLine(t),
                note = when (t.state) {
                    SftpTransfer.State.DONE -> if (upload) t.remotePath else t.location
                    SftpTransfer.State.FAILED -> t.error
                    SftpTransfer.State.CANCELLED -> t.error
                    SftpTransfer.State.CANCELLING -> if (connected) null else leftoverWarning(t, gaveUp)
                    else -> null
                },
                noteColor = when (t.state) {
                    SftpTransfer.State.FAILED -> TitanColors.Danger
                    SftpTransfer.State.CANCELLING, SftpTransfer.State.CANCELLED -> TitanColors.Warning
                    else -> TitanColors.Mute
                },
                marker = marker,
                markerColor = color,
                onClick = if (openable) ({ onOpen(t.id) }) else null,
                trailing = {
                    when {
                        t.finished -> GlyphButton("[-]", onClick = { onDismiss(t.id) })
                        // Nothing left to stop: it only waits to delete its partial file.
                        t.state == SftpTransfer.State.CANCELLING -> Unit
                        else -> GlyphButton("[x]", onClick = { onCancel(t.id) }, color = TitanColors.Danger)
                    }
                },
            )
        }
    }
}

/** `12 MB / 40 MB · 30 %` and what the transfer is doing. */
private fun transferLine(t: SftpTransfer): String {
    val done = SftpPaths.formatSize(t.transferred)
    val amount = t.size?.let { size ->
        val percent = if (size > 0) (t.transferred * 100 / size) else 100
        "$done / ${SftpPaths.formatSize(size)}  ·  $percent %"
    } ?: done
    val files = t.files?.let { "${t.filesDone} de $it archivos  ·  " }.orEmpty()
    val skipped = if (t.skipped > 0) "  ·  ${t.skipped} sin descargar (enlaces a carpetas, especiales o sin permiso)" else ""
    val upload = t.direction == SftpTransfer.Direction.UPLOAD
    return when (t.state) {
        SftpTransfer.State.RUNNING -> if (t.folder && t.files == null) "mirando qué contiene…" else files + amount
        SftpTransfer.State.WAITING -> "$files$amount  ·  esperando la conexión"
        SftpTransfer.State.DONE -> when {
            upload -> "subido  ·  ${SftpPaths.formatSize(t.transferred)}"
            t.folder -> "descargada  ·  ${t.files ?: 0} archivos  ·  ${SftpPaths.formatSize(t.transferred)}$skipped"
            else -> "descargado  ·  ${SftpPaths.formatSize(t.transferred)}  ·  toca para abrir"
        }
        SftpTransfer.State.CANCELLING -> "cancelando  ·  se borrará el archivo a medias al volver la conexión"
        SftpTransfer.State.FAILED -> "falló en $done"
        SftpTransfer.State.CANCELLED -> "cancelada"
    }
}

/** What stays on the destination if the connection of a cancelled upload never comes back. */
private fun leftoverWarning(t: SftpTransfer, gaveUp: Boolean): String {
    val partial = t.partial ?: t.remotePath
    val folder = SftpPaths.parent(partial) ?: "/"
    val text = "Si la conexión no vuelve, quedará ${partial.substringAfterLast('/')} en $folder."
    return if (gaveUp) "$text Pulsa «reconectar» para borrarlo." else text
}

/** The phase, with "reconectar" when it is not connected. */
@Composable
private fun SftpStatusStrip(
    status: TabStatus,
    onReconnect: () -> Unit,
    causeOpen: Boolean,
    onToggleCause: (() -> Unit)?,
    onExitFullscreen: (() -> Unit)?,
) {
    val (marker, color, label) = when (status.phase) {
        TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "SFTP")
        TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
        TabPhase.PAUSED -> Triple("[-]", TitanColors.Mute, "En pausa")
        TabPhase.DISCONNECTED -> Triple("[x]", TitanColors.Danger, "Caída")
        TabPhase.FAILED -> Triple("[x]", TitanColors.Danger, "Fallo")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(TitanColors.Canvas)
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.width(TitanDimens.SpaceSm))
        Text(
            if (status.phase == TabPhase.CONNECTED) label else status.detail ?: label,
            style = MaterialTheme.typography.labelSmall,
            color = if (causeOpen) TitanColors.Accent else TitanColors.Mute,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .then(if (onToggleCause != null) Modifier.clickable(onClick = onToggleCause) else Modifier),
        )
        if (status.phase == TabPhase.RECONNECTING || status.phase == TabPhase.DISCONNECTED || status.phase == TabPhase.FAILED) {
            Text(
                "reconectar",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Accent,
                modifier = Modifier.clickable(onClick = onReconnect).padding(horizontal = TitanDimens.SpaceXs),
            )
        }
        if (onExitFullscreen != null) {
            GlyphButton("[v]", onClick = onExitFullscreen, color = TitanColors.Mute)
        }
    }
}
