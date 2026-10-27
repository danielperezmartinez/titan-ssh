package io.github.danielperezmartinez.titanssh.ui

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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.onDemandScripts
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.terminal.AgentInsights
import io.github.danielperezmartinez.titanssh.terminal.AgentKey
import io.github.danielperezmartinez.titanssh.terminal.AgentManager
import io.github.danielperezmartinez.titanssh.terminal.KeyBar
import io.github.danielperezmartinez.titanssh.terminal.KeyBarKey
import io.github.danielperezmartinez.titanssh.terminal.SessionInstances
import io.github.danielperezmartinez.titanssh.terminal.SessionManager
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.isAndroidRuntime
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The "Sesiones" area: a multi-tab terminal. With no tabs open (or when the user
 * asks for another) it shows the launcher — the saved sessions and the
 * connection each resolves to; launching opens a live tab on the SSH engine. With
 * tabs open it shows the tab strip (switch, close, reorder) and the active
 * terminal, with a two-pane split on desktop and a full-screen tab on Android.
 */
@Composable
fun SessionsArea(
    controller: ConfigController,
    manager: SessionManager,
    agents: AgentManager,
    onEditSession: (sessionId: String) -> Unit,
    onOpenConfig: () -> Unit,
    onOpenAgent: (AgentHost) -> Unit,
    openLauncher: Boolean = false,
    onConfigureKeyBar: () -> Unit = {},
) {
    val config by controller.state.collectAsState()
    val keyBar = remember(config.settings.keyBar) { KeyBar.resolve(config.settings.keyBar) }
    val softKeyboard = SoftKeyboardSettings(
        suggestions = config.settings.keyboardSuggestions,
        showToggle = config.settings.keyboardSuggestionsButton,
        onToggle = { controller.setKeyboardSuggestions(!controller.state.value.settings.keyboardSuggestions) },
    )
    val tabs by manager.tabs.collectAsState()
    val activeId by manager.activeId.collectAsState()
    val openInstances by manager.openInstances.collectAsState()

    // [openLauncher] brings the launcher back after editing a session from it,
    // even with tabs open.
    var showLauncher by remember { mutableStateOf(openLauncher) }
    var splitEnabled by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    // The tab whose menu is open, and the one being renamed.
    var menuTabId by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    // The tab whose menu is asking to confirm "Terminar en el destino".
    var confirmingTerminateId by remember { mutableStateOf<String?>(null) }
    // An SFTP tab whose closing would leave partial uploads on the destination (ADR-0024).
    var closingId by remember { mutableStateOf<String?>(null) }

    if (tabs.isEmpty() || showLauncher) {
        Launcher(
            controller = controller,
            agents = agents,
            openInstances = openInstances,
            onOpenAgent = onOpenAgent,
            onLaunch = { resolved, instance, mustBeNew ->
                manager.open(resolved, instance, mustBeNew)
                showLauncher = false
            },
            onEdit = onEditSession,
            onOpenConfig = onOpenConfig,
            onBack = if (tabs.isNotEmpty()) ({ showLauncher = false }) else null,
        )
        return
    }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    val scope = rememberCoroutineScope()
    val watch by agents.watch.state.collectAsState()

    // A new parallel instance of [tab]'s saved session (ADR-0020), numbered
    // past those open in tabs and those its agent last reported alive.
    fun openParallel(tab: SessionTab) {
        val saved = config.sessions.firstOrNull { it.id == tab.resolved.session.id }
        val resolved = saved?.let { runCatching { config.resolve(it) }.getOrNull() } ?: tab.resolved
        val live = watch.observations[AgentKey.of(resolved.endpoint).id]
            ?.let { AgentInsights.instancesOf(it, resolved.session.id).keys }.orEmpty()
        val next = SessionInstances.nextParallel(openInstances[resolved.session.id].orEmpty() + live)
        manager.open(resolved, next, mustBeNew = true)
    }

    if (fullscreen && isAndroidRuntime()) {
        val sftp = active.sftp
        if (sftp != null) {
            // Its own strip has room for the way out; floating, it would cover the path bar.
            SftpView(active, sftp, Modifier.fillMaxSize(), onExitFullscreen = { fullscreen = false })
            return
        }
        Box(Modifier.fillMaxSize()) {
            TabContent(active, Modifier.fillMaxSize(), scripts = config.menuScripts(active), keyBar, onConfigureKeyBar, softKeyboard)
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
            onClose = { id ->
                // Connected, closing deletes the partial uploads itself: no need to ask.
                val tab = tabs.firstOrNull { it.id == id }
                val leftovers = tab?.sftp?.takeIf { tab.status.value.phase != TabPhase.CONNECTED }?.partialUploads().orEmpty()
                if (leftovers.isEmpty()) manager.close(id) else closingId = id
            },
            onMove = { from, to -> manager.move(from, to) },
            onNew = { showLauncher = true },
            split = splitEnabled,
            onToggleSplit = if (!isAndroidRuntime()) ({ splitEnabled = !splitEnabled }) else null,
            onFullscreen = if (isAndroidRuntime()) ({ fullscreen = true }) else null,
            menuTabId = menuTabId,
            onMenu = {
                menuTabId = it
                confirmingTerminateId = null
            },
            onDismissMenu = {
                menuTabId = null
                confirmingTerminateId = null
            },
            menu = { tab ->
                TabMenuItem("[~]", "Renombrar", onClick = {
                    menuTabId = null
                    renamingId = tab.id
                })
                if (tab.isTerminal && SessionInstances.supportsInstances(tab.resolved.session)) {
                    TabMenuItem("[+]", "Abrir en paralelo", glyphColor = TitanColors.Accent, onClick = {
                        menuTabId = null
                        openParallel(tab)
                    })
                }
                if (tab.isTerminal) {
                    TabMenuItem("[d]", "Abrir explorador de archivos", onClick = {
                        menuTabId = null
                        scope.launch { manager.openExplorer(tab) }
                    })
                }
                // Ends this instance in the agent, like the panel's Terminar:
                // its tabs close first so none reconnects and starts it again.
                if (tab.isTerminal && tab.resolved.session.resilienceLevel == ResilienceLevel.AGENT) {
                    if (confirmingTerminateId == tab.id) {
                        TabMenuNote("¿Terminar? Se cierra lo que tenga en marcha")
                        TabMenuItem("[x]", "Sí", glyphColor = TitanColors.Danger, onClick = {
                            menuTabId = null
                            confirmingTerminateId = null
                            val saved = config.sessions.firstOrNull { it.id == tab.resolved.session.id } ?: tab.resolved.session
                            agents.terminate(saved, tab.instance.number.value)
                        })
                        TabMenuItem("[<]", "No", onClick = { confirmingTerminateId = null })
                    } else {
                        TabMenuItem("[x]", "Terminar en el destino", glyphColor = TitanColors.Danger, onClick = {
                            confirmingTerminateId = tab.id
                        })
                    }
                }
            },
        )
        Hairline()
        tabs.firstOrNull { it.id == closingId }?.let { tab ->
            val leftovers = tab.sftp?.partialUploads().orEmpty()
            ConfirmRow(
                question = if (leftovers.size == 1) "Hay 1 subida sin terminar" else "Hay ${leftovers.size} subidas sin terminar",
                subtitle = "Sin conexión no se pueden borrar: si cierras ahora, quedará " + leftoverList(leftovers) + ".",
                confirmLabel = "[x] Cerrar igualmente",
                onConfirm = {
                    closingId = null
                    manager.close(tab.id)
                },
                onCancel = { closingId = null },
                marker = "[!]",
                markerColor = TitanColors.Warning,
                modifier = Modifier.padding(horizontal = TitanDimens.SpaceSm),
            )
            Hairline()
        }
        tabs.firstOrNull { it.id == renamingId }?.let { tab ->
            RenameStrip(
                tab = tab,
                inAgent = tab.isTerminal && tab.resolved.session.resilienceLevel == ResilienceLevel.AGENT,
                onSave = { name ->
                    if (tab.isTerminal) {
                        val saved = config.sessions.firstOrNull { it.id == tab.resolved.session.id } ?: tab.resolved.session
                        agents.rename(saved, tab.instance.number.value, name)
                    } else {
                        tab.rename(name)
                        null
                    }
                },
                onDone = { renamingId = null },
            )
            Hairline()
        }

        val secondary = if (splitEnabled && !isAndroidRuntime()) secondaryTab(tabs, active) else null
        if (secondary != null) {
            Row(Modifier.fillMaxSize()) {
                TabContent(active, Modifier.weight(1f).fillMaxHeight(), scripts = config.menuScripts(active), keyBar, onConfigureKeyBar, softKeyboard)
                Box(Modifier.width(TitanDimens.Hairline).fillMaxHeight().background(TitanColors.HairlineStrong))
                TabContent(secondary, Modifier.weight(1f).fillMaxHeight(), scripts = config.menuScripts(secondary), keyBar, onConfigureKeyBar, softKeyboard)
            }
        } else {
            TabContent(active, Modifier.fillMaxSize(), scripts = config.menuScripts(active), keyBar, onConfigureKeyBar, softKeyboard)
        }
    }
}

/** `.a.titan-part en /srv` or `2 archivos a medias (.a.titan-part, .b.titan-part) en /srv`. */
private fun leftoverList(partials: List<String>): String {
    val folders = partials.map { it.substringBeforeLast('/').ifEmpty { "/" } }.distinct()
    val names = partials.joinToString(", ") { it.substringAfterLast('/') }
    val where = if (folders.size == 1) " en ${folders[0]}" else " en el destino"
    return if (partials.size == 1) names + where else "${partials.size} archivos a medias ($names)$where"
}

/**
 * A tab's pane: its terminal, its mouse pad (ADR-0016), its remote desktop
 * (the mouse pad with the screen, ADR-0029) or its file browser (ADR-0021).
 */
@Composable
private fun TabContent(
    tab: SessionTab,
    modifier: Modifier,
    scripts: List<SessionScript>,
    keyBar: List<KeyBarKey>,
    onConfigureKeyBar: () -> Unit,
    softKeyboard: SoftKeyboardSettings,
) {
    val sftp = tab.sftp
    if (tab.isMousepad || tab.isDesktop) {
        MousepadView(tab, modifier, keyBar = keyBar, onConfigureKeyBar = onConfigureKeyBar, softKeyboard = softKeyboard)
    } else if (sftp != null) {
        SftpView(tab, sftp, modifier)
    } else {
        TerminalView(
            tab, modifier, scripts = scripts, keyBar = keyBar, onConfigureKeyBar = onConfigureKeyBar,
            softKeyboard = softKeyboard,
        )
    }
}

/**
 * The scripts [tab]'s menu offers, from the current config so library edits show
 * up in open tabs; falls back to the session as opened if it was since deleted.
 */
private fun TitanConfig.menuScripts(tab: SessionTab): List<SessionScript> {
    val opened = tab.resolved.session
    return onDemandScripts(sessions.firstOrNull { it.id == opened.id } ?: opened)
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
    menuTabId: String?,
    onMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    menu: @Composable ColumnScope.(SessionTab) -> Unit,
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
                        menuOpen = menuTabId == tab.id,
                        onMenu = { onMenu(tab.id) },
                        onDismissMenu = onDismissMenu,
                        menu = { menu(tab) },
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

/**
 * One tab of the strip. A long press opens its [menu]: lifting the finger
 * leaves it open, while dragging on instead closes it and moves the tab
 * (decided with the user, [[Renombrar las pestañas y las sesiones activas]]).
 * On desktop a right click opens it too.
 */
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
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
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
    val currentOnMenu by rememberUpdatedState(onMenu)
    val currentOnDismissMenu by rememberUpdatedState(onDismissMenu)
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .onGloballyPositioned { onSize(it.size.width) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { this.translationX = floatOffset }
            .padding(end = TitanDimens.SpaceXs),
    ) {
        Row(
            Modifier
                .background(
                    when {
                        dragging -> TitanColors.SurfaceElevated
                        selected || menuOpen -> TitanColors.Surface
                        else -> TitanColors.Canvas
                    },
                )
                .border(
                    TitanDimens.Hairline,
                    if (selected || dragging || menuOpen) TitanColors.Accent else TitanColors.HairlineStrong,
                    RoundedCornerShape(TitanDimens.RadiusSm),
                )
                // A right click is desktop's long press: taken on the initial
                // pass so the tab's click never sees it.
                .pointerInput(tab.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                currentOnMenu()
                            }
                        }
                    }
                }
                .clickable(onClick = onSelect)
                // The menu opens after a long press, so a quick horizontal drag
                // still scrolls the strip and a tap still selects the tab. Only
                // a drag past the touch slop after it moves the tab: a finger
                // that merely trembles leaves the menu open.
                .pointerInput(tab.id) {
                    var moved = 0f
                    var reordering = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            moved = 0f
                            reordering = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnMenu()
                        },
                        onDragEnd = { if (reordering) currentOnDragEnd() },
                        onDragCancel = { if (reordering) currentOnDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (reordering) {
                                currentOnDrag(dragAmount.x)
                            } else {
                                moved += dragAmount.x
                                if (abs(moved) > viewConfiguration.touchSlop) {
                                    reordering = true
                                    currentOnDismissMenu()
                                    currentOnDragStart()
                                    currentOnDrag(moved)
                                }
                            }
                        },
                    )
                }
                .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(marker, style = MaterialTheme.typography.labelSmall, color = markerColor)
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            // A new parallel tab can still move to another number when it connects.
            val instance by tab.instance.number.collectAsState()
            val customName by tab.customName.collectAsState()
            Text(
                SessionInstances.label(tab.resolved.session.name, instance, customName),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) TitanColors.Ink else TitanColors.Mute,
            )
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            MiniGlyph("[x]", onClose, color = TitanColors.Mute)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            containerColor = TitanColors.SurfaceElevated,
            content = menu,
        )
    }
}

/** One action of a tab's menu: an ASCII [glyph] and its [label]. */
@Composable
private fun TabMenuItem(glyph: String, label: String, onClick: () -> Unit, glyphColor: Color = TitanColors.Body) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, style = MaterialTheme.typography.bodyMedium, color = glyphColor)
                Spacer(Modifier.width(TitanDimens.SpaceSm))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TitanColors.Ink)
            }
        },
        onClick = onClick,
    )
}

/** A line of a tab's menu that only explains, e.g. what a confirmation is about. */
@Composable
private fun TabMenuNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = TitanColors.Mute,
        modifier = Modifier.padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceXs),
    )
}

/**
 * Renames [tab] in place, under the strip. [onSave] applies the name and
 * returns why it could not be kept, or null; at level 3 ([inAgent]) the
 * destination's agent keeps it, elsewhere only this tab does. Blank goes back
 * to the session's own name.
 */
@Composable
private fun RenameStrip(
    tab: SessionTab,
    inAgent: Boolean,
    onSave: suspend (String) -> String?,
    onDone: () -> Unit,
) {
    val instance by tab.instance.number.collectAsState()
    val current by tab.customName.collectAsState()
    NameEditor(
        key = tab.id,
        initial = current.orEmpty(),
        placeholder = SessionInstances.label(tab.resolved.session.name, instance),
        hint = nameHint(inAgent),
        onSave = onSave,
        onDone = onDone,
        modifier = Modifier
            .background(TitanColors.Surface)
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
    )
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
    TabPhase.PAUSED -> "[-]" to TitanColors.Mute
    TabPhase.DISCONNECTED -> "[x]" to TitanColors.Danger
    TabPhase.FAILED -> "[x]" to TitanColors.Danger
}
