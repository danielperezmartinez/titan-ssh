package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.terminal.AccessoryKey
import io.github.danielperezmartinez.titanssh.terminal.AgentDiagnostics
import io.github.danielperezmartinez.titanssh.terminal.DefaultAccessoryKeys
import io.github.danielperezmartinez.titanssh.terminal.EffectiveLevel
import io.github.danielperezmartinez.titanssh.terminal.ModifierKind
import io.github.danielperezmartinez.titanssh.terminal.ResilienceStatus
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TerminalKeys
import io.github.danielperezmartinez.titanssh.terminal.TerminalMultiplexer
import io.github.danielperezmartinez.titanssh.terminal.TunnelState
import io.github.danielperezmartinez.titanssh.terminal.TunnelStatus
import io.github.danielperezmartinez.titanssh.terminal.isAndroidRuntime
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * How long the pane size must hold still before the grid is resized. While the
 * soft keyboard slides in the pane changes every frame; resizing the PTY on
 * each of them made the remote redraw over and over.
 */
private const val RESIZE_SETTLE_MILLIS = 150L

/**
 * Renders one [SessionTab]: the terminal grid, a status/host-key overlay, and
 * input. Keyboard input is captured on all platforms via key events (physical
 * keyboards and desktop); on Android an accessory key bar supplies the keys the
 * soft keyboard lacks (Esc, Tab, Ctrl, Alt, arrows, `| / - ~`) plus paste, and a
 * hidden capture field brings up the soft keyboard for text.
 *
 * [scripts] are the ones the user can launch by hand (ADR-0013): the status
 * strip opens a menu with them while the tab is connected.
 */
@Composable
fun TerminalView(tab: SessionTab, modifier: Modifier = Modifier, scripts: List<SessionScript> = emptyList()) {
    val status by tab.status.collectAsState()
    val resilience by tab.resilience.collectAsState()
    val tunnels by tab.tunnels.collectAsState()
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val monoFamily: FontFamily = MaterialTheme.typography.bodyMedium.fontFamily ?: FontFamily.Monospace
    val fontSize = (tab.resolved.appearance.fontSize ?: 13).sp

    // Sticky modifiers driven by the accessory bar (applied to the next key).
    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    // Android: the soft keyboard is raised by focusing the hidden capture field
    // below (a plain focusable pane never brings up the IME), so tapping the
    // terminal routes focus there and asks the controller to show it.
    val keyboardFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val viewport = remember(tab.id) { TerminalViewport() }

    // Typing returns to the live screen; cursor keys follow the remote's DECCKM.
    fun send(bytes: ByteArray) {
        viewport.toBottom()
        val out = TerminalKeys.inCursorMode(bytes, tab.snapshot.value.applicationCursorKeys)
        scope.launch { tab.sendBytes(out) }
    }

    // Applies sticky Ctrl/Alt to a typed string, then clears them.
    fun sendTyped(textInput: String) {
        val bytes = when {
            stickyCtrl -> textInput.firstOrNull()?.let { TerminalKeys.ctrl(it) } ?: TerminalKeys.text(textInput)
            stickyAlt -> TerminalKeys.alt(textInput)
            else -> TerminalKeys.text(textInput)
        }
        stickyCtrl = false
        stickyAlt = false
        send(bytes)
    }

    // The grid is painted cell by cell (TerminalCanvas), so the column count only
    // depends on the monospace advance, measured once the bundled font is in.
    val density = LocalDensity.current
    val textStyle = remember(monoFamily, fontSize) {
        TextStyle(fontFamily = monoFamily, fontSize = fontSize, color = TerminalFg)
    }
    val cell = rememberCellMetrics(textStyle)
    val paddingX = with(density) { TitanDimens.SpaceXs.toPx() }

    // Resize the grid (emulator + PTY) from the pane size, but only once it holds
    // still: the first size goes at once, later ones after RESIZE_SETTLE_MILLIS.
    // The pane size is read here and in the draw phase only, never in
    // composition, so a sliding keyboard does not recompose the screen per frame.
    LaunchedEffect(tab.id, cell, paddingX) {
        var settled = false
        snapshotFlow { viewport.paneSize }
            .filter { it.width > 0 && it.height > 0 }
            .map { gridFor(it, cell, paddingX) }
            .distinctUntilChanged()
            .collectLatest { (columns, rows) ->
                if (settled) delay(RESIZE_SETTLE_MILLIS)
                settled = true
                tab.resize(columns, rows)
            }
    }

    // imePadding shrinks the terminal above the soft keyboard (with adjustResize)
    // instead of the window panning up and hiding the input line.
    Column(modifier.fillMaxSize().background(TerminalBgColor).imePadding()) {
        if (pendingHostKey != null) {
            HostKeyPromptBar(
                fingerprint = pendingHostKey!!.info.fingerprintSha256,
                keyType = pendingHostKey!!.info.keyType,
                host = pendingHostKey!!.info.host,
                onAccept = { pendingHostKey!!.accept() },
                onReject = { pendingHostKey!!.reject() },
            )
            Hairline()
        }

        var scriptsOpen by remember(tab.id) { mutableStateOf(false) }
        var tunnelsOpen by remember(tab.id) { mutableStateOf(false) }
        val canRunScripts = scripts.isNotEmpty() && status.phase == TabPhase.CONNECTED
        StatusStrip(
            status,
            resilience,
            onEnableLinger = { scope.launch { tab.enableLinger() } },
            onReconnect = { tab.reconnectNow() },
            scriptsOpen = scriptsOpen && canRunScripts,
            onToggleScripts = if (canRunScripts) ({ scriptsOpen = !scriptsOpen; tunnelsOpen = false }) else null,
            tunnels = tunnels,
            tunnelsOpen = tunnelsOpen,
            onToggleTunnels = { tunnelsOpen = !tunnelsOpen; scriptsOpen = false },
        )
        if (scriptsOpen && canRunScripts) {
            ScriptsMenu(scripts) { script ->
                tab.runScript(script)
                scriptsOpen = false
            }
            Hairline()
        }
        if (tunnelsOpen && tunnels.isNotEmpty()) {
            TunnelsPanel(tunnels)
            Hairline()
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size -> viewport.paneSize = size }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event -> handleKeyEvent(event, { send(it) }, { sendTyped(it) }) }
                // Drag or wheel through the scrollback; new input returns to the bottom.
                .scrollable(
                    orientation = Orientation.Vertical,
                    state = rememberScrollableState { delta -> viewport.scrollBy(delta) },
                )
                // No ripple: the touch only focuses/raises the keyboard; a Material
                // indication would break the flat, chrome-free terminal aesthetic.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (isAndroidRuntime()) {
                        keyboardFocus.requestFocus()
                        keyboardController?.show()
                    } else {
                        focusRequester.requestFocus()
                    }
                },
        ) {
            TerminalCanvas(
                snapshots = tab.snapshot,
                viewport = viewport,
                style = textStyle,
                cell = cell,
                paddingX = paddingX,
                showCursor = status.phase == TabPhase.CONNECTED,
                modifier = Modifier.fillMaxSize(),
            )
        }

        LaunchedEffect(tab.id) { focusRequester.requestFocus() }

        if (isAndroidRuntime()) {
            Hairline()
            // Derived, so only a visible/hidden flip recomposes, not every frame
            // of the keyboard animation.
            val ime = WindowInsets.ime
            val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
            AndroidInputBar(
                keyboardFocus = keyboardFocus,
                keyboardVisible = keyboardVisible,
                onToggleKeyboard = {
                    if (keyboardVisible) {
                        keyboardController?.hide()
                    } else {
                        keyboardFocus.requestFocus()
                        keyboardController?.show()
                    }
                },
                stickyCtrl = stickyCtrl,
                stickyAlt = stickyAlt,
                onModifier = { kind ->
                    when (kind) {
                        ModifierKind.CTRL -> { stickyCtrl = !stickyCtrl; stickyAlt = false }
                        ModifierKind.ALT -> { stickyAlt = !stickyAlt; stickyCtrl = false }
                    }
                },
                onSend = { bytes -> send(bytes) },
                onText = { text -> sendTyped(text) },
                onEnter = { send(TerminalKeys.special(TerminalKeys.SpecialKey.ENTER)) },
                onBackspace = { send(TerminalKeys.special(TerminalKeys.SpecialKey.BACKSPACE)) },
                onPaste = {
                    clipboard.getText()?.text?.let { send(TerminalKeys.paste(it, tab.snapshot.value.bracketedPaste)) }
                },
            )
        }
    }
}

/** Translates a Compose key event into terminal bytes. Returns true when handled. */
private fun handleKeyEvent(
    event: KeyEvent,
    send: (ByteArray) -> Unit,
    sendTyped: (String) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    keyToSpecial(event)?.let { special ->
        send(TerminalKeys.special(special))
        return true
    }

    val codePoint = event.utf16CodePoint
    if (event.isCtrlPressed && codePoint != 0) {
        TerminalKeys.ctrl(codePoint.toChar())?.let { bytes ->
            send(bytes)
            return true
        }
    }

    if (codePoint != 0 && !event.isCtrlPressed && !event.isMetaPressed) {
        val text = codePointToString(codePoint)
        if (event.isAltPressed) send(TerminalKeys.alt(text)) else sendTyped(text)
        return true
    }
    return false
}

private fun keyToSpecial(event: KeyEvent): TerminalKeys.SpecialKey? {
    // Compare by keyCode name to stay platform-agnostic in common code.
    return when (event.key.keyCode) {
        androidx.compose.ui.input.key.Key.Enter.keyCode,
        androidx.compose.ui.input.key.Key.NumPadEnter.keyCode -> TerminalKeys.SpecialKey.ENTER
        androidx.compose.ui.input.key.Key.Backspace.keyCode -> TerminalKeys.SpecialKey.BACKSPACE
        androidx.compose.ui.input.key.Key.Tab.keyCode -> TerminalKeys.SpecialKey.TAB
        androidx.compose.ui.input.key.Key.Escape.keyCode -> TerminalKeys.SpecialKey.ESCAPE
        androidx.compose.ui.input.key.Key.Delete.keyCode -> TerminalKeys.SpecialKey.DELETE
        androidx.compose.ui.input.key.Key.DirectionUp.keyCode -> TerminalKeys.SpecialKey.UP
        androidx.compose.ui.input.key.Key.DirectionDown.keyCode -> TerminalKeys.SpecialKey.DOWN
        androidx.compose.ui.input.key.Key.DirectionLeft.keyCode -> TerminalKeys.SpecialKey.LEFT
        androidx.compose.ui.input.key.Key.DirectionRight.keyCode -> TerminalKeys.SpecialKey.RIGHT
        androidx.compose.ui.input.key.Key.MoveHome.keyCode -> TerminalKeys.SpecialKey.HOME
        androidx.compose.ui.input.key.Key.MoveEnd.keyCode -> TerminalKeys.SpecialKey.END
        androidx.compose.ui.input.key.Key.PageUp.keyCode -> TerminalKeys.SpecialKey.PAGE_UP
        androidx.compose.ui.input.key.Key.PageDown.keyCode -> TerminalKeys.SpecialKey.PAGE_DOWN
        else -> null
    }
}

private fun codePointToString(codePoint: Int): String =
    if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val v = codePoint - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

/**
 * A thin strip showing the tab's connection phase with a semantic marker/color,
 * the resilience level it really runs at, and below it any level-3 issue
 * ([[Diagnóstico cuando el nivel 3 no está disponible]]): why the tab degraded,
 * or the systemd warning with its fix. The notice can be dismissed; a new issue
 * shows again. While the tab reconnects, or once it is down, "reconectar" tries
 * again at once in the same tab.
 */
@Composable
private fun StatusStrip(
    status: io.github.danielperezmartinez.titanssh.terminal.TabStatus,
    resilience: ResilienceStatus,
    onEnableLinger: () -> Unit,
    onReconnect: () -> Unit,
    scriptsOpen: Boolean,
    onToggleScripts: (() -> Unit)?,
    tunnels: List<TunnelStatus>,
    tunnelsOpen: Boolean,
    onToggleTunnels: () -> Unit,
) {
    val (marker, color, label) = when (status.phase) {
        TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "Conectado")
        TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
        TabPhase.DISCONNECTED -> Triple("[x]", TitanColors.Danger, "Caída")
        TabPhase.FAILED -> Triple("[x]", TitanColors.Danger, "Fallo")
    }
    val level = resilience.level?.takeIf { status.phase == TabPhase.CONNECTED }?.let { levelLabel(it, resilience) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(TitanColors.Canvas)
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, fontFamily = MaterialTheme.typography.bodyLarge.fontFamily, color = color, fontSize = 13.sp)
        Spacer(Modifier.width(TitanDimens.SpaceSm))
        Text(
            (status.detail ?: label) + (level?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.Mute,
            modifier = Modifier.weight(1f),
        )
        if (status.phase == TabPhase.RECONNECTING || status.phase == TabPhase.DISCONNECTED || status.phase == TabPhase.FAILED) {
            Text(
                "reconectar",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Accent,
                modifier = Modifier.clickable(onClick = onReconnect).padding(horizontal = TitanDimens.SpaceXs),
            )
        }
        if (tunnels.isNotEmpty()) {
            val active = tunnels.count { it.state == TunnelState.ACTIVE }
            val trouble = tunnels.any { it.state == TunnelState.FAILED || it.detail != null }
            Text(
                "[=] túneles $active/${tunnels.size}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    tunnelsOpen -> TitanColors.Accent
                    trouble -> TitanColors.Warning
                    else -> TitanColors.Body
                },
                modifier = Modifier.clickable(onClick = onToggleTunnels).padding(horizontal = TitanDimens.SpaceXs),
            )
        }
        if (onToggleScripts != null) {
            Text(
                "[>] scripts",
                style = MaterialTheme.typography.labelSmall,
                color = if (scriptsOpen) TitanColors.Accent else TitanColors.Body,
                modifier = Modifier.clickable(onClick = onToggleScripts).padding(horizontal = TitanDimens.SpaceXs),
            )
        }
    }
    val issue = resilience.issue ?: return
    var dismissed by remember(issue) { mutableStateOf(false) }
    if (dismissed) return
    Row(
        Modifier
            .fillMaxWidth()
            .background(TitanColors.Canvas)
            .padding(start = TitanDimens.SpaceMd, end = TitanDimens.SpaceSm, bottom = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.Top,
    ) {
        // A degraded or threatened session is a real session state: warning.
        Text("[-]", fontFamily = MaterialTheme.typography.bodyLarge.fontFamily, color = TitanColors.Warning, fontSize = 13.sp)
        Spacer(Modifier.width(TitanDimens.SpaceSm))
        Column(Modifier.weight(1f)) {
            Text(AgentDiagnostics.describe(issue), style = MaterialTheme.typography.labelSmall, color = TitanColors.Mute)
            if (issue.code == AgentDiagnostics.E_SYSTEMD_KILL) {
                Text(
                    "activar linger",
                    style = MaterialTheme.typography.labelSmall,
                    color = TitanColors.Accent,
                    modifier = Modifier.clickable(onClick = onEnableLinger).padding(vertical = TitanDimens.SpaceXs),
                )
                resilience.lingerError?.let { error ->
                    Text(
                        "No se pudo activar: $error. Quien administre el destino puede hacerlo con " +
                            "sudo loginctl enable-linger <usuario>.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TitanColors.Mute,
                    )
                }
            }
        }
        Text(
            "[x]",
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.Mute,
            modifier = Modifier.clickable { dismissed = true }.padding(horizontal = TitanDimens.SpaceXs),
        )
    }
}

/**
 * The tab's scripts menu: the session's on-demand scripts, then the library's.
 * Picking one sends it to the terminal.
 */
@Composable
private fun ScriptsMenu(scripts: List<SessionScript>, onRun: (SessionScript) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(TitanColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TitanDimens.SpaceMd),
    ) {
        scripts.forEach { script ->
            val fromLibrary = script.libraryScriptId != null
            ListRow(
                marker = "[>]",
                title = script.label.ifBlank { "(sin nombre)" },
                subtitle = (if (fromLibrary) "biblioteca · " else "") + script.body.lineSequence().firstOrNull().orEmpty().take(60),
                onClick = { onRun(script) },
                markerColor = TitanColors.Accent,
            )
        }
    }
}

/**
 * The tab's tunnels ([[Ejecutar los túneles de las sesiones]]): each one's
 * state and, for a failed one or a failed connection through it, the reason.
 */
@Composable
private fun TunnelsPanel(tunnels: List<TunnelStatus>) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(TitanColors.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TitanDimens.SpaceMd),
    ) {
        tunnels.forEach { status ->
            val t = status.tunnel
            val (marker, color, state) = when (status.state) {
                TunnelState.ACTIVE ->
                    if (status.detail == null) Triple("[+]", TitanColors.Success, "activo")
                    else Triple("[-]", TitanColors.Warning, "activo")
                TunnelState.WAITING -> Triple("[-]", TitanColors.Mute, "esperando la conexión")
                TunnelState.FAILED -> Triple("[x]", TitanColors.Danger, "no se abrió")
            }
            ListRow(
                marker = marker,
                title = t.label.ifBlank { tunnelSummary(t) },
                subtitle = listOfNotNull(tunnelSummary(t), state, status.detail).joinToString("  ·  "),
                onClick = {},
                markerColor = color,
            )
        }
    }
}

/** How the strip names the level a tab really runs at. */
private fun levelLabel(level: EffectiveLevel, resilience: ResilienceStatus): String = when (level) {
    EffectiveLevel.AGENT -> "nivel 3 · agente"
    EffectiveLevel.MULTIPLEXER ->
        "nivel 2 · " + if (resilience.multiplexer == TerminalMultiplexer.Kind.SCREEN) "screen" else "tmux"
    EffectiveLevel.BASE -> "nivel 1"
}

/** Inline first-contact host-key confirmation bar (TOFU, ADR-0005). */
@Composable
private fun HostKeyPromptBar(
    fingerprint: String,
    keyType: String,
    host: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TitanColors.Surface)
            .padding(TitanDimens.SpaceMd),
    ) {
        Text(
            "[?] Clave de host nueva para $host",
            style = MaterialTheme.typography.bodyLarge,
            color = TitanColors.Ink,
        )
        Spacer(Modifier.height(TitanDimens.SpaceXs))
        Caption("$keyType  ·  $fingerprint")
        Spacer(Modifier.height(TitanDimens.SpaceSm))
        Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
            TitanButton("[ok] Confiar", onClick = onAccept, kind = ButtonKind.PRIMARY)
            TitanButton("[x] Rechazar", onClick = onReject, kind = ButtonKind.DANGER)
        }
    }
}

/**
 * Android accessory bar: the shell keys the soft keyboard lacks, sticky Ctrl/Alt,
 * paste, plus a hidden capture field that raises the soft keyboard and forwards
 * typed characters (best-effort; verified path is physical keys + these buttons).
 */
@Composable
private fun AndroidInputBar(
    keyboardFocus: FocusRequester,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    stickyCtrl: Boolean,
    stickyAlt: Boolean,
    onModifier: (ModifierKind) -> Unit,
    onSend: (ByteArray) -> Unit,
    onText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(TitanColors.Canvas)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TitanDimens.SpaceXs, vertical = TitanDimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The keys scroll horizontally in the remaining width; the keyboard
            // toggle is pinned on the right, outside the scroll, always visible.
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DefaultAccessoryKeys.forEach { key ->
                    when (key) {
                        is AccessoryKey.Modifier -> {
                            val active = when (key.kind) {
                                ModifierKind.CTRL -> stickyCtrl
                                ModifierKind.ALT -> stickyAlt
                            }
                            AccessoryButton(key.label, active) { onModifier(key.kind) }
                        }
                        is AccessoryKey.Send -> AccessoryButton(key.label, false) { onSend(key.bytes) }
                    }
                }
                AccessoryButton("Pegar", false, onPaste)
            }
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            AccessoryButton("[kbd]", active = keyboardVisible, onClick = onToggleKeyboard)
        }
        SoftKeyboardCapture(
            focusRequester = keyboardFocus,
            onText = onText,
            onEnter = onEnter,
            onBackspace = onBackspace,
        )
    }
}


@Composable
private fun AccessoryButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) TitanColors.Accent.copy(alpha = 0.15f) else TitanColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) TitanColors.Accent else TitanColors.Body,
        )
    }
}

/**
 * A near-invisible text field that raises the Android soft keyboard and forwards
 * typed characters using the sentinel-anchor trick (the field always holds one
 * zero-width anchor; growth = typed text, shrink = a backspace). IME prediction
 * can interfere, so this is best-effort and complements the accessory bar; it is
 * only mounted on Android.
 */
@Composable
private fun SoftKeyboardCapture(
    focusRequester: FocusRequester,
    onText: (String) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
) {
    val anchor = "​"
    var value by remember { mutableStateOf(TextFieldValue(anchor, TextRange(anchor.length))) }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val text = new.text
            if (text.length > anchor.length) {
                // Multi-line field, so the IME shows a real return key (↵): a
                // newline in the added text is an Enter, and the keyboard stays up.
                val added = text.substring(anchor.length)
                val buf = StringBuilder()
                added.forEach { ch ->
                    if (ch == '\n' || ch == '\r') {
                        if (buf.isNotEmpty()) {
                            onText(buf.toString())
                            buf.clear()
                        }
                        onEnter()
                    } else {
                        buf.append(ch)
                    }
                }
                if (buf.isNotEmpty()) onText(buf.toString())
            } else if (text.length < anchor.length) {
                onBackspace()
            }
            value = TextFieldValue(anchor, TextRange(anchor.length))
        },
        singleLine = false,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                // Enter arrives as a '\n' via onValueChange (kept multi-line);
                // here we only need the hardware Backspace fallback.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.keyCode) {
                    androidx.compose.ui.input.key.Key.Backspace.keyCode -> { onBackspace(); true }
                    else -> false
                }
            },
    )
}
