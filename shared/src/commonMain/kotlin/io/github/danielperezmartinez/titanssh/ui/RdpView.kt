package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.danielperezmartinez.titanssh.rdp.PendingRdpCertificate
import io.github.danielperezmartinez.titanssh.rdp.RdpFrameImage
import io.github.danielperezmartinez.titanssh.rdp.RdpScreen
import io.github.danielperezmartinez.titanssh.rdp.formatFingerprint
import io.github.danielperezmartinez.titanssh.rdp.send
import io.github.danielperezmartinez.titanssh.terminal.AgentFrame
import io.github.danielperezmartinez.titanssh.terminal.InputKeys
import io.github.danielperezmartinez.titanssh.terminal.KeyBarAction
import io.github.danielperezmartinez.titanssh.terminal.KeyBarKey
import io.github.danielperezmartinez.titanssh.terminal.MousepadMotion
import io.github.danielperezmartinez.titanssh.terminal.MousepadTyping
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TabStatus
import io.github.danielperezmartinez.titanssh.terminal.isAndroidRuntime
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch

/**
 * An RDP tab (ADR-0028): the remote desktop, scaled to fit, with the server's
 * pointer drawn in it.
 *
 * - **Desktop app**: the mouse and the keyboard act on the desktop directly;
 *   the pointer goes where the mouse is.
 * - **Android**: the screen is a touchpad over the desktop, with the mouse
 *   pad's gestures (ADR-0016): one finger moves the pointer and a tap clicks,
 *   two fingers scroll and a two-finger tap is a right click, and holding a
 *   finger drags. Below, the user's key bar and the system keyboard.
 *
 * Keys and text go through the mouse pad's own input frames ([RdpScreen.send]),
 * so the key bar's sticky modifiers and shortcuts work the same here.
 */
@Composable
fun RdpView(
    tab: SessionTab,
    screen: RdpScreen,
    modifier: Modifier = Modifier,
    keyBar: List<KeyBarKey> = emptyList(),
    onConfigureKeyBar: () -> Unit = {},
) {
    val status by tab.status.collectAsState()
    val frame by screen.frame.collectAsState()
    val certificate by screen.certificate.collectAsState()
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val changedHostKey by tab.changedHostKey.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val android = isAndroidRuntime()
    val surfaceFocus = remember { FocusRequester() }

    Column(modifier.fillMaxSize().background(TitanColors.TerminalBg).then(if (android) Modifier.imePadding() else Modifier)) {
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
        certificate?.let { pending ->
            RdpCertificateBar(pending, target = tab.resolved.session.rdp.let { "${it.targetHost}:${it.targetPort}" })
            Hairline()
        }
        var causeOpen by remember(tab.id) { mutableStateOf(false) }
        val cause = status.cause
        RdpStatusStrip(
            status,
            onReconnect = { tab.reconnectNow() },
            causeOpen = causeOpen && cause != null,
            onToggleCause = if (cause != null) ({ causeOpen = !causeOpen }) else null,
        )
        Hairline()
        if (causeOpen && cause != null) {
            StatusDetailPanel(cause)
            Hairline()
        }

        // Where the desktop is drawn inside the surface, to map pointer positions.
        var surface by remember { mutableStateOf(IntSize.Zero) }
        val current = frame
        val placement = remember(surface, current?.width, current?.height) {
            current?.let { fit(surface, it.width, it.height) }
        }
        val frameImage = remember(current?.width, current?.height) {
            current?.let { RdpFrameImage(it.width, it.height) }
        }
        // Plain memory, not state: the draw reads and writes it.
        val shownVersion = remember(frameImage) { longArrayOf(-1L) }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    surface = size
                    // The desktop app asks for a desktop the size of the pane; a
                    // phone, for a laptop-sized one it zooms out of.
                    if (!android && size.width > 0 && size.height > 0) {
                        tab.rdpDesktopWidth = size.width
                        tab.rdpDesktopHeight = size.height
                    }
                }
                .focusRequester(surfaceFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val frames = hardwareKeyFrames(event) ?: return@onPreviewKeyEvent false
                    frames.forEach(screen::send)
                    true
                }
                .then(
                    if (android) {
                        Modifier.touchpad(tab, screen, density.density)
                    } else {
                        Modifier.desktopMouse(screen, { placement }, surfaceFocus)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (current != null && frameImage != null && placement != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val shown = frame ?: return@Canvas
                    if (shown.version != shownVersion[0] && shown.width == current.width && shown.height == current.height) {
                        frameImage.update(shown.pixels)
                        shownVersion[0] = shown.version
                    }
                    drawImage(
                        frameImage.image,
                        dstOffset = IntOffset(placement.left, placement.top),
                        dstSize = IntSize(placement.width, placement.height),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            if (current == null || status.phase != TabPhase.CONNECTED) {
                SurfaceNote(status, connectedOnce = current != null)
            }
        }
        LaunchedEffect(tab.id) { if (!android) surfaceFocus.requestFocus() }
        DisposableEffect(tab.id) { onDispose { screen.releaseAll() } }

        if (android) {
            Hairline()
            RdpKeyboard(screen, keyBar, onConfigureKeyBar)
        }
    }
}

/** Where a desktop of [width] × [height] goes in [surface]: as big as fits, centred. */
private data class Placement(val left: Int, val top: Int, val width: Int, val height: Int) {
    /** The desktop pixel under [position] of the surface. */
    fun toDesktop(position: Offset, desktopWidth: Int, desktopHeight: Int): Pair<Int, Int> {
        val x = ((position.x - left) * desktopWidth / width).toInt()
        val y = ((position.y - top) * desktopHeight / height).toInt()
        return x.coerceIn(0, desktopWidth - 1) to y.coerceIn(0, desktopHeight - 1)
    }
}

private fun fit(surface: IntSize, width: Int, height: Int): Placement? {
    if (surface.width <= 0 || surface.height <= 0 || width <= 0 || height <= 0) return null
    val scale = minOf(surface.width.toFloat() / width, surface.height.toFloat() / height)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return Placement((surface.width - w) / 2, (surface.height - h) / 2, w, h)
}

/** The mouse on the desktop app: position, buttons and wheel straight to the desktop. */
private fun Modifier.desktopMouse(
    screen: RdpScreen,
    placement: () -> Placement?,
    focus: FocusRequester,
): Modifier = pointerInput(screen) {
    var primary = false
    var secondary = false
    var tertiary = false
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val frame = screen.frame.value
            val place = placement()
            if (frame != null && place != null && change.type != PointerType.Touch) {
                val (x, y) = place.toDesktop(change.position, frame.width, frame.height)
                when (event.type) {
                    PointerEventType.Move, PointerEventType.Enter -> screen.mouseMove(x, y)
                    PointerEventType.Press, PointerEventType.Release -> {
                        if (event.type == PointerEventType.Press) focus.requestFocus()
                        screen.mouseMove(x, y)
                        val buttons = event.buttons
                        if (buttons.isPrimaryPressed != primary) {
                            primary = buttons.isPrimaryPressed
                            screen.mouseButton(0, primary)
                        }
                        if (buttons.isSecondaryPressed != secondary) {
                            secondary = buttons.isSecondaryPressed
                            screen.mouseButton(2, secondary)
                        }
                        if (buttons.isTertiaryPressed != tertiary) {
                            tertiary = buttons.isTertiaryPressed
                            screen.mouseButton(1, tertiary)
                        }
                    }
                    PointerEventType.Scroll -> {
                        // Compose's scroll delta is positive downwards; RDP's wheel, upwards.
                        val dy = change.scrollDelta.y
                        val dx = change.scrollDelta.x
                        if (dy != 0f) screen.send(AgentFrame.Scroll(0, (-dy * NOTCH).toInt()))
                        if (dx != 0f) screen.send(AgentFrame.Scroll((dx * NOTCH).toInt(), 0))
                    }
                    else -> Unit
                }
            }
            change.consume()
        }
    }
}

private const val NOTCH = 120

/** The phone's screen as a touchpad over the desktop, with the mouse pad's gestures. */
private fun Modifier.touchpad(tab: SessionTab, screen: RdpScreen, density: Float): Modifier = pointerInput(tab.id) {
    val motion = MousepadMotion(tab.resolved.session.mousepad)
    val slop = viewConfiguration.touchSlop
    val holdMillis = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        motion.reset()
        fun click(button: Int) {
            screen.send(AgentFrame.PointerButton(button, pressed = true))
            screen.send(AgentFrame.PointerButton(button, pressed = false))
        }
        when (val start = decideGesture(slop, holdMillis)) {
            is Gesture.Tap -> click(
                when (start.fingers) {
                    1 -> InputKeys.BUTTON_LEFT
                    2 -> InputKeys.BUTTON_RIGHT
                    else -> InputKeys.BUTTON_MIDDLE
                },
            )
            Gesture.Hold -> {
                screen.send(AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = true))
                track(first.uptimeMillis, dragging = true, scrolling = false, density, motion, screen::send)
                screen.send(AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = false))
            }
            is Gesture.Slide -> track(
                first.uptimeMillis, dragging = false, scrolling = start.fingers >= 2,
                density, motion, screen::send,
            )
            Gesture.Done -> Unit
        }
    }
}

/** The key bar and the system keyboard, as on the mouse pad. */
@Composable
private fun RdpKeyboard(screen: RdpScreen, keyBar: List<KeyBarKey>, onConfigureKeyBar: () -> Unit) {
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val keyboardFocus = remember { FocusRequester() }
    var sticky by remember { mutableIntStateOf(0) }

    fun consumeSticky(): Int = sticky.also { sticky = 0 }

    fun pressKey(key: KeyBarKey) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (val action = key.action) {
            is KeyBarAction.Modifier -> sticky = sticky xor action.mod
            KeyBarAction.Paste -> clipboard.getText()?.text?.let { MousepadTyping.pasted(it).forEach(screen::send) }
            else -> key.desktopFrames(consumeSticky())?.forEach(screen::send)
        }
    }

    val ime = WindowInsets.ime
    val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    Column(Modifier.fillMaxWidth().background(TitanColors.Canvas)) {
        KeyBarRow(
            keys = keyBar,
            inTerminal = false,
            sticky = sticky,
            onKey = { pressKey(it) },
            onConfigure = onConfigureKeyBar,
            keyboardVisible = keyboardVisible,
            onToggleKeyboard = {
                if (keyboardVisible) {
                    keyboardController?.hide()
                } else {
                    keyboardFocus.requestFocus()
                    keyboardController?.show()
                }
            },
        )
        SoftKeyboardCapture(
            focusRequester = keyboardFocus,
            onText = { text -> MousepadTyping.typed(text, consumeSticky()).forEach(screen::send) },
            onEnter = { screen.send(AgentFrame.Key(InputKeys.ENTER, consumeSticky())) },
            onBackspace = { screen.send(AgentFrame.Key(InputKeys.BACKSPACE, consumeSticky())) },
        )
    }
}

/**
 * The server's certificate, before any credential goes to it (ADR-0028 §6):
 * new, or different from the one trusted for this server.
 */
@Composable
private fun RdpCertificateBar(pending: PendingRdpCertificate, target: String) {
    val changed = pending.previous != null
    Column(Modifier.fillMaxWidth().background(TitanColors.Surface).padding(TitanDimens.SpaceMd)) {
        Text(
            if (changed) "[!] El certificado del escritorio $target ha cambiado" else "[?] Certificado nuevo del escritorio $target",
            style = MaterialTheme.typography.bodyLarge,
            color = if (changed) TitanColors.Warning else TitanColors.Ink,
        )
        Spacer(Modifier.height(TitanDimens.SpaceXs))
        if (pending.subject.isNotBlank()) Caption(pending.subject)
        Caption("SHA-256  ·  ${formatFingerprint(pending.sha256)}")
        if (changed) {
            Spacer(Modifier.height(TitanDimens.SpaceXs))
            Caption(
                "Antes era ${formatFingerprint(pending.previous.orEmpty())}. Puede ser normal (Windows renueva el " +
                    "suyo) o que alguien se haga pasar por el escritorio: confírmalo antes de seguir.",
                color = TitanColors.Warning,
            )
        }
        Spacer(Modifier.height(TitanDimens.SpaceSm))
        Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
            TitanButton("[ok] Confiar", onClick = { pending.accept() }, kind = ButtonKind.PRIMARY)
            TitanButton("[x] Rechazar", onClick = { pending.reject() }, kind = ButtonKind.DANGER)
        }
    }
}

/** The phase, with the way to reconnect and the cause behind a failure. */
@Composable
private fun RdpStatusStrip(
    status: TabStatus,
    onReconnect: () -> Unit,
    causeOpen: Boolean,
    onToggleCause: (() -> Unit)?,
) {
    val (marker, color, label) = when (status.phase) {
        TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "Escritorio remoto")
        TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
        TabPhase.PAUSED -> Triple("[-]", TitanColors.Mute, "En pausa")
        TabPhase.DISCONNECTED -> Triple("[x]", TitanColors.Danger, "Caída")
        else -> Triple("[x]", TitanColors.Danger, "Fallo")
    }
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
    }
}

/** What the surface says while there is no live desktop. */
@Composable
private fun SurfaceNote(status: TabStatus, connectedOnce: Boolean) {
    val (text, color) = when (status.phase) {
        TabPhase.FAILED, TabPhase.DISCONNECTED -> (status.detail ?: "Sin conexión con el escritorio.") to TitanColors.Danger
        TabPhase.CONNECTED -> "Esperando al escritorio…" to TitanColors.Mute
        else -> (if (connectedOnce) "Reconectando con el escritorio…" else "Abriendo el escritorio remoto…") to TitanColors.Mute
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.background(TitanColors.Canvas.copy(alpha = 0.85f)).padding(TitanDimens.SpaceXl),
    )
}
