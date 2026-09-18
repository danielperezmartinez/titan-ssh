package im.gar.titanssh.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
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
            onMove = { from, to -> manager.move(from, to) },
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
    onMove: (from: Int, to: Int) -> Unit,
    onNew: () -> Unit,
    split: Boolean,
    onToggleSplit: (() -> Unit)?,
    onFullscreen: (() -> Unit)?,
) {
    // Chip width per tab id (including its trailing gap), captured at layout and
    // stable across live reorders. From these we derive each slot's center by
    // prefix sum over the current order — no absolute-coordinate APIs needed.
    val widths = remember { mutableStateMapOf<String, Int>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    var startCenter by remember { mutableStateOf(0f) }
    var pointerDx by remember { mutableStateOf(0f) }

    fun slotLeft(index: Int): Float {
        var left = 0
        for (j in 0 until index) left += widths[tabs[j].id] ?: 0
        return left.toFloat()
    }

    fun centerOf(index: Int): Float =
        slotLeft(index) + (widths[tabs.getOrNull(index)?.id] ?: 0) / 2f

    // Every chip's width is known: only then are the derived slot positions final,
    // so we snap (not animate) into place until then to avoid an opening slide-in.
    val measured = tabs.all { widths.containsKey(it.id) }

    Row(
        Modifier.fillMaxWidth().background(TitanColors.Canvas),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                key(tab.id) {
                    val isDragging = tab.id == draggingId
                    // The floating chip follows the finger; its slot (this index)
                    // stays put, so the row shows the gap it will drop into.
                    val translationX = if (isDragging) startCenter + pointerDx - centerOf(index) else 0f
                    TabChip(
                        tab = tab,
                        selected = tab.id == activeId,
                        dragging = isDragging,
                        translationX = translationX,
                        slotLeft = slotLeft(index),
                        measured = measured,
                        onSelect = { onSelect(tab.id) },
                        onClose = { onClose(tab.id) },
                        onSize = { widths[tab.id] = it },
                        onDragStart = {
                            draggingId = tab.id
                            dragIndex = index
                            startCenter = centerOf(index)
                            pointerDx = 0f
                        },
                        onDrag = { dx ->
                            pointerDx += dx
                            // Reorder live: as the finger crosses a neighbour's
                            // center, move the tab there so the others shift now.
                            val fingerX = startCenter + pointerDx
                            var target = dragIndex
                            while (target < tabs.lastIndex && fingerX > centerOf(target + 1)) target++
                            while (target > 0 && fingerX < centerOf(target - 1)) target--
                            if (target != dragIndex) {
                                onMove(dragIndex, target)
                                dragIndex = target
                            }
                        },
                        onDragEnd = {
                            draggingId = null
                            dragIndex = -1
                            pointerDx = 0f
                        },
                    )
                }
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
    dragging: Boolean,
    translationX: Float,
    slotLeft: Float,
    measured: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onSize: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val status by tab.status.collectAsState()
    val (marker, markerColor) = statusMarker(status.phase)
    // Placement animation for the chips that are NOT being dragged: when a reorder
    // changes this chip's slot, it slides from its old slot to the new one instead
    // of jumping. `placement` tracks the animated slot position; the applied offset
    // is (animated − target), which starts at the old delta and eases to 0. The
    // dragged chip floats with the finger (translationX) and is kept in sync via
    // snapTo, and we snap until all widths are measured to avoid an opening slide.
    val placement = remember { Animatable(slotLeft) }
    LaunchedEffect(slotLeft, dragging, measured) {
        if (dragging || !measured) placement.snapTo(slotLeft)
        else placement.animateTo(slotLeft, animationSpec = tween(durationMillis = 180))
    }
    val floatOffset = if (dragging) translationX else placement.value - slotLeft
    // pointerInput keeps its block alive across recompositions (its key, tab.id, is
    // stable), so it would otherwise capture the drag callbacks — and the index /
    // tabs / geometry they close over — from the FIRST composition. After the first
    // reorder those are stale, so a second drag computes the wrong target and snaps.
    // rememberUpdatedState makes the long-lived gesture always call the latest ones.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            .onGloballyPositioned { onSize(it.size.width) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { this.translationX = floatOffset }
            .padding(end = TitanDimens.SpaceXs)
            .background(
                when {
                    dragging -> TitanColors.SurfaceElevated
                    selected -> TitanColors.Surface
                    else -> TitanColors.Canvas
                },
            )
            .border(
                TitanDimens.Hairline,
                if (selected || dragging) TitanColors.Accent else TitanColors.HairlineStrong,
                RoundedCornerShape(TitanDimens.RadiusSm),
            )
            .clickable(onClick = onSelect)
            // Drag-to-reorder starts after a long press, so a quick horizontal
            // drag still scrolls the strip and a tap still selects the tab.
            .pointerInput(tab.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount.x)
                    },
                )
            }
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
