package im.gar.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.ResolvedConnection
import im.gar.titanssh.config.resolve
import im.gar.titanssh.terminal.SessionManager
import im.gar.titanssh.terminal.SessionTab
import im.gar.titanssh.terminal.TabPhase
import im.gar.titanssh.terminal.isAndroidRuntime
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens

/**
 * The "Sesiones" area: a multi-tab terminal. With no tabs open (or when the user
 * asks for another) it shows the launcher — the saved sessions and the
 * connection each resolves to; launching opens a live tab on the SSH engine. With
 * tabs open it shows the tab strip (switch, close, reorder) and the active
 * terminal, with a two-pane split on desktop and a full-screen tab on Android.
 */
@Composable
fun SessionsArea(controller: ConfigController, manager: SessionManager) {
    val config by controller.state.collectAsState()
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeId.collectAsState()

    var showLauncher by remember { mutableStateOf(false) }
    var splitEnabled by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    if (tabs.isEmpty() || showLauncher) {
        Launcher(
            controller = controller,
            onLaunch = { resolved ->
                manager.open(resolved)
                showLauncher = false
            },
            onBack = if (tabs.isNotEmpty()) ({ showLauncher = false }) else null,
        )
        return
    }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.first()

    if (fullscreen && isAndroidRuntime()) {
        Box(Modifier.fillMaxSize()) {
            TerminalView(active, Modifier.fillMaxSize())
            GlyphButton(
                "[v]",
                onClick = { fullscreen = false },
                color = TitanColors.Mute,
                modifier = Modifier.align(Alignment.TopEnd).background(TitanColors.Canvas),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TabStrip(
            tabs = tabs,
            activeId = active.id,
            onSelect = { manager.activate(it) },
            onClose = { manager.close(it) },
            onMoveLeft = { manager.moveLeft(it) },
            onMoveRight = { manager.moveRight(it) },
            onNew = { showLauncher = true },
            split = splitEnabled,
            onToggleSplit = if (!isAndroidRuntime()) ({ splitEnabled = !splitEnabled }) else null,
            onFullscreen = if (isAndroidRuntime()) ({ fullscreen = true }) else null,
        )
        Hairline()

        val secondary = if (splitEnabled && !isAndroidRuntime()) secondaryTab(tabs, active) else null
        if (secondary != null) {
            Row(Modifier.fillMaxSize()) {
                TerminalView(active, Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(TitanDimens.Hairline).fillMaxHeight().background(TitanColors.HairlineStrong))
                TerminalView(secondary, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            TerminalView(active, Modifier.fillMaxSize())
        }
    }
}

/** Picks the pane to show beside the active one in split view (its neighbour). */
private fun secondaryTab(tabs: List<SessionTab>, active: SessionTab): SessionTab? {
    if (tabs.size < 2) return null
    val index = tabs.indexOf(active)
    return tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
}

@Composable
private fun TabStrip(
    tabs: List<SessionTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onMoveLeft: (String) -> Unit,
    onMoveRight: (String) -> Unit,
    onNew: () -> Unit,
    split: Boolean,
    onToggleSplit: (() -> Unit)?,
    onFullscreen: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().background(TitanColors.Canvas),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    selected = tab.id == activeId,
                    onSelect = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                    onMoveLeft = { onMoveLeft(tab.id) },
                    onMoveRight = { onMoveRight(tab.id) },
                )
            }
        }
        GlyphButton("[+]", onClick = onNew, color = TitanColors.Accent)
        if (onToggleSplit != null) {
            GlyphButton("[#]", onClick = onToggleSplit, color = if (split) TitanColors.Accent else TitanColors.Mute)
        }
        if (onFullscreen != null) {
            GlyphButton("[^]", onClick = onFullscreen, color = TitanColors.Mute)
        }
    }
}

@Composable
private fun TabChip(
    tab: SessionTab,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val status by tab.status.collectAsState()
    val (marker, markerColor) = statusMarker(status.phase)
    Row(
        Modifier
            .padding(end = TitanDimens.SpaceXs)
            .background(if (selected) TitanColors.Surface else TitanColors.Canvas)
            .border(
                TitanDimens.Hairline,
                if (selected) TitanColors.Accent else TitanColors.HairlineStrong,
                RoundedCornerShape(TitanDimens.RadiusSm),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, style = MaterialTheme.typography.labelSmall, color = markerColor)
        Spacer(Modifier.width(TitanDimens.SpaceXs))
        Text(
            tab.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TitanColors.Ink else TitanColors.Mute,
        )
        if (selected) {
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            MiniGlyph("[<]", onMoveLeft)
            MiniGlyph("[>]", onMoveRight)
        }
        Spacer(Modifier.width(TitanDimens.SpaceXs))
        MiniGlyph("[x]", onClose, color = TitanColors.Mute)
    }
}

@Composable
private fun MiniGlyph(glyph: String, onClick: () -> Unit, color: Color = TitanColors.Mute) {
    Box(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = TitanDimens.SpaceXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Maps a phase to the ASCII marker + semantic color used in the strip. */
private fun statusMarker(phase: TabPhase): Pair<String, Color> = when (phase) {
    TabPhase.CONNECTING -> "[-]" to TitanColors.Warning
    TabPhase.CONNECTED -> "[+]" to TitanColors.Success
    TabPhase.RECONNECTING -> "[-]" to TitanColors.Warning
    TabPhase.DISCONNECTED -> "[x]" to TitanColors.Danger
    TabPhase.FAILED -> "[x]" to TitanColors.Danger
}

/**
 * The launcher: saved sessions and the connection each resolves to. Launching a
 * resolvable session opens a live tab (the wiring this task adds); an unresolved
 * one is flagged and cannot be launched.
 */
@Composable
private fun Launcher(
    controller: ConfigController,
    onLaunch: (ResolvedConnection) -> Unit,
    onBack: (() -> Unit)?,
) {
    val config by controller.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().padding(TitanDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
                Text(
                    "Volver a las pestañas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TitanColors.Mute,
                    modifier = Modifier.padding(start = TitanDimens.SpaceSm),
                )
            }
            Hairline()
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
            item {
                Caption("Lanza una sesión guardada para abrirla en una pestaña de terminal.")
                Spacer(Modifier.height(TitanDimens.SpaceMd))
                Hairline()
            }
            if (config.sessions.isEmpty()) {
                item { EmptyState("No hay sesiones. Créalas en Configuración → Sesiones.") }
            }
            items(config.sessions) { session ->
                val resolved = runCatching { config.resolve(session) }.getOrNull()
                val subtitle = if (resolved != null) {
                    "${resolved.endpoint.username}@${resolved.endpoint.host}:${resolved.endpoint.port}"
                } else {
                    "host no encontrado — revisa la configuración"
                }
                ListRow(
                    marker = if (resolved != null) "[>]" else "[x]",
                    title = session.name,
                    subtitle = subtitle,
                    onClick = { resolved?.let(onLaunch) },
                    markerColor = if (resolved != null) TitanColors.Body else TitanColors.Danger,
                    trailing = {
                        if (resolved != null) {
                            TitanButton("[>] Lanzar", onClick = { onLaunch(resolved) }, kind = ButtonKind.SECONDARY)
                        }
                    },
                )
                Hairline()
            }
        }
    }
}
