package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import io.github.danielperezmartinez.titanssh.formatLocalDateTime
import io.github.danielperezmartinez.titanssh.sftp.SftpBrowser
import io.github.danielperezmartinez.titanssh.sftp.SftpPaths
import io.github.danielperezmartinez.titanssh.sftp.SftpTransfer
import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TabStatus
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * An SFTP tab (ADR-0021): the destination's folders, read-only. Tapping a
 * folder opens it; tapping a file unfolds its details and "Descargar", which
 * saves it to the device's Downloads. The path on top can be walked back by
 * its parts or typed; the downloads tray at the bottom shows their progress,
 * and a download caught by a dropped connection waits and goes on by itself.
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
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val changedHostKey by tab.changedHostKey.collectAsState()
    val scope = rememberCoroutineScope()

    var showHidden by remember(tab.id) { mutableStateOf(false) }
    // The file whose details and download action are unfolded; one at a time.
    var openFile by remember(tab.id) { mutableStateOf<String?>(null) }
    var editingPath by remember(tab.id) { mutableStateOf<String?>(null) }
    var causeOpen by remember(tab.id) { mutableStateOf(false) }
    LaunchedEffect(listing.path) { openFile = null }

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
        if (editing != null) {
            Row(
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
        } else {
            PathBar(
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val entries = remember(listing.entries, showHidden) { SftpPaths.sorted(listing.entries, showHidden) }
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
                        EntryRow(
                            entry,
                            open = openFile == entry.path,
                            onClick = {
                                when {
                                    entry.isDirectory -> browser.open(entry.path)
                                    entry.kind == SftpEntry.Kind.OTHER -> Unit
                                    else -> openFile = if (openFile == entry.path) null else entry.path
                                }
                            },
                            onDownload = {
                                browser.download(entry)
                                openFile = null
                            },
                        )
                        Hairline()
                    }
                }
            }
        }

        if (transfers.isNotEmpty()) {
            Hairline()
            TransfersTray(
                transfers,
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

@Composable
private fun EntryRow(entry: SftpEntry, open: Boolean, onClick: () -> Unit, onDownload: () -> Unit) {
    val (marker, color) = when (entry.kind) {
        SftpEntry.Kind.DIRECTORY -> "[d]" to TitanColors.Accent
        SftpEntry.Kind.LINK_TO_DIRECTORY -> "[@]" to TitanColors.Accent
        SftpEntry.Kind.FILE -> "[-]" to TitanColors.Body
        SftpEntry.Kind.LINK -> "[@]" to TitanColors.Body
        SftpEntry.Kind.OTHER -> "[?]" to TitanColors.Stone
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
        expanded = open,
        expandedContent = {
            val facts = listOfNotNull(
                entry.size?.let { "$it bytes" },
                entry.permissions?.let(SftpPaths::formatPermissions),
                "enlace".takeIf { entry.kind == SftpEntry.Kind.LINK },
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) Caption(facts, Modifier.padding(vertical = TitanDimens.SpaceXs))
            ListRow(marker = "[v]", title = "Descargar", subtitle = "a Descargas", onClick = onDownload)
        },
    )
}

/** The downloads of this tab, newest last. */
@Composable
private fun TransfersTray(
    transfers: List<SftpTransfer>,
    onCancel: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .background(TitanColors.Surface)
            .padding(horizontal = TitanDimens.SpaceSm),
    ) {
        items(transfers, key = { it.id }) { t ->
            val (marker, color) = when (t.state) {
                SftpTransfer.State.RUNNING -> "[v]" to TitanColors.Accent
                SftpTransfer.State.WAITING -> "[-]" to TitanColors.Warning
                SftpTransfer.State.DONE -> "[+]" to TitanColors.Success
                SftpTransfer.State.FAILED -> "[x]" to TitanColors.Danger
                SftpTransfer.State.CANCELLED -> "[x]" to TitanColors.Mute
            }
            ListRow(
                title = t.name,
                subtitle = transferLine(t),
                note = when (t.state) {
                    SftpTransfer.State.DONE -> t.location
                    SftpTransfer.State.FAILED -> t.error
                    else -> null
                },
                noteColor = if (t.state == SftpTransfer.State.FAILED) TitanColors.Danger else TitanColors.Mute,
                marker = marker,
                markerColor = color,
                onClick = if (t.state == SftpTransfer.State.DONE) ({ onOpen(t.id) }) else null,
                trailing = {
                    if (t.finished) {
                        GlyphButton("[-]", onClick = { onDismiss(t.id) })
                    } else {
                        GlyphButton("[x]", onClick = { onCancel(t.id) }, color = TitanColors.Danger)
                    }
                },
            )
        }
    }
}

/** `12 MB / 40 MB · 30 %` and what the download is doing. */
private fun transferLine(t: SftpTransfer): String {
    val done = SftpPaths.formatSize(t.transferred)
    val amount = t.size?.let { size ->
        val percent = if (size > 0) (t.transferred * 100 / size) else 100
        "$done / ${SftpPaths.formatSize(size)}  ·  $percent %"
    } ?: done
    return when (t.state) {
        SftpTransfer.State.RUNNING -> amount
        SftpTransfer.State.WAITING -> "$amount  ·  esperando la conexión"
        SftpTransfer.State.DONE -> "descargado  ·  ${SftpPaths.formatSize(t.transferred)}  ·  toca para abrir"
        SftpTransfer.State.FAILED -> "falló en $done"
        SftpTransfer.State.CANCELLED -> "cancelada"
    }
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
        TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "SFTP · solo lectura")
        TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
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
