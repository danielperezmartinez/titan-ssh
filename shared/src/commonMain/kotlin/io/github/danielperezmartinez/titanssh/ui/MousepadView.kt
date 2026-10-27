package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.danielperezmartinez.titanssh.terminal.ScreenView
import io.github.danielperezmartinez.titanssh.terminal.ScreenViewport
import io.github.danielperezmartinez.titanssh.terminal.drawScreenSurface
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielperezmartinez.titanssh.terminal.AgentFrame
import io.github.danielperezmartinez.titanssh.terminal.InputKeys
import io.github.danielperezmartinez.titanssh.terminal.KeyBarAction
import io.github.danielperezmartinez.titanssh.terminal.KeyBarKey
import io.github.danielperezmartinez.titanssh.terminal.MousepadMotion
import io.github.danielperezmartinez.titanssh.terminal.MousepadStatus
import io.github.danielperezmartinez.titanssh.terminal.MousepadTyping
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TabStatus
import io.github.danielperezmartinez.titanssh.terminal.isAndroidRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * A mouse pad tab (ADR-0016), or a remote desktop tab (ADR-0029), which is
 * the same with the destination's screen behind the surface: there a mouse
 * points straight at the screen (its position, buttons and wheel), while touch
 * keeps the touchpad gestures below, with the remote cursor drawn on the image
 * and a zoom that follows it.
 *
 * The mouse pad is the phone as the destination's touchpad and keyboard. The
 * surface takes the gestures of a laptop touchpad:
 *
 * - one finger moves the pointer, and a tap is a left click;
 * - two fingers scroll, and a two-finger tap is a right click (three, middle);
 * - holding a finger still presses the left button, to drag until it lifts.
 *
 * Below it, buttons to hold as a mouse's, the user's key bar ([KeyBarRow], the
 * same [keyBar] as the terminal's: sticky Ctrl/Alt/Shift/Win and the keys a
 * phone keyboard lacks; its `[*]` calls [onConfigureKeyBar]), and the system
 * keyboard. Events go out through [SessionTab.sendInput]; the strip on top says
 * whether they reach the desktop.
 */
@Composable
fun MousepadView(
    tab: SessionTab,
    modifier: Modifier = Modifier,
    keyBar: List<KeyBarKey> = emptyList(),
    onConfigureKeyBar: () -> Unit = {},
) {
    val status by tab.status.collectAsState()
    val pad by tab.mousepad.collectAsState()
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val changedHostKey by tab.changedHostKey.collectAsState()
    val scope = rememberCoroutineScope()
    val motion = remember(tab.id) { MousepadMotion(tab.resolved.session.mousepad) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val keyboardFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }

    // Remote desktop only (ADR-0029): the screen, how it fits the surface and
    // whether a mouse hovers it (then its own pointer stands for the cursor).
    val remote = tab.remoteScreen
    // The screen changes with every frame and cursor move: it is read where it
    // is used (drawing, pointer mapping), so they do not recompose the view.
    val noScreen = remember { MutableStateFlow(ScreenView()) }
    val screenView = (remote?.view ?: noScreen).collectAsState()
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(tab.id) { mutableIntStateOf(1) }
    var hovering by remember { mutableStateOf(false) }

    // Sticky modifiers (InputKeys.MOD_*) for the next key or character.
    var sticky by remember(tab.id) { mutableIntStateOf(0) }
    // Win was toggled on and nothing was typed since: toggling it off taps it
    // alone, which opens the Start menu, as on a real keyboard.
    var winAlone by remember(tab.id) { mutableStateOf(false) }

    fun send(frame: AgentFrame) = tab.sendInput(frame)

    fun consumeSticky(): Int = sticky.also {
        sticky = 0
        winAlone = false
    }

    fun sendKey(key: Int) = send(AgentFrame.Key(key, consumeSticky()))

    fun sendText(text: String) = MousepadTyping.typed(text, consumeSticky()).forEach(::send)

    fun toggleModifier(mod: Int) {
        if (sticky and mod != 0) {
            sticky = sticky and mod.inv()
            if (mod == InputKeys.MOD_META && winAlone) send(AgentFrame.Key(InputKeys.META))
            if (mod == InputKeys.MOD_META) winAlone = false
        } else {
            sticky = sticky or mod
            winAlone = mod == InputKeys.MOD_META
        }
    }

    // A key bar key: a modifier toggles, the rest go out with the sticky ones.
    fun pressKey(key: KeyBarKey) {
        when (val action = key.action) {
            is KeyBarAction.Modifier -> toggleModifier(action.mod)
            KeyBarAction.Paste -> clipboard.getText()?.text?.let { MousepadTyping.pasted(it).forEach(::send) }
            else -> key.desktopFrames(consumeSticky())?.forEach(::send)
        }
    }

    fun click(button: Int) {
        send(AgentFrame.PointerButton(button, pressed = true))
        send(AgentFrame.PointerButton(button, pressed = false))
    }

    Column(modifier.fillMaxSize().background(TitanColors.TerminalBg).imePadding()) {
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
        var causeOpen by remember(tab.id) { mutableStateOf(false) }
        val cause = status.cause
        MousepadStatusStrip(
            status,
            pad,
            onReconnect = { tab.reconnectNow() },
            causeOpen = causeOpen && cause != null,
            onToggleCause = if (cause != null) ({ causeOpen = !causeOpen }) else null,
            // Zoom is for touch: a mouse points at the screen itself.
            zoom = if (remote != null && isAndroidRuntime()) zoom else null,
            onZoom = { zoom = if (zoom >= MAX_ZOOM) 1 else zoom + 1 },
        )
        Hairline()
        if (causeOpen && cause != null) {
            StatusDetailPanel(cause)
            Hairline()
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // A zoomed remote screen draws past the surface: keep it inside.
                .clipToBounds()
                .focusRequester(surfaceFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val frames = hardwareKeyFrames(event) ?: return@onPreviewKeyEvent false
                    frames.forEach(::send)
                    true
                }
                .onSizeChanged { surfaceSize = it }
                .then(
                    if (remote != null) {
                        Modifier.pointerInput(tab.id) {
                            mouseOnScreen(
                                viewport = {
                                    screenViewport(screenView.value, surfaceSize.width.toFloat(), surfaceSize.height.toFloat(), zoom)
                                },
                                onHover = { hovering = it },
                                onPress = { surfaceFocus.requestFocus() },
                                send = ::send,
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .pointerInput(tab.id) {
                    val slop = viewConfiguration.touchSlop
                    val holdMillis = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        // On a remote desktop a mouse points at the screen (mouseOnScreen).
                        if (remote != null && first.type == PointerType.Mouse) return@awaitEachGesture
                        if (!isAndroidRuntime()) surfaceFocus.requestFocus()
                        motion.reset()
                        when (val start = decideGesture(slop, holdMillis)) {
                            is Gesture.Tap -> click(
                                when (start.fingers) {
                                    1 -> InputKeys.BUTTON_LEFT
                                    2 -> InputKeys.BUTTON_RIGHT
                                    else -> InputKeys.BUTTON_MIDDLE
                                },
                            )
                            Gesture.Hold -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                send(AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = true))
                                track(first.uptimeMillis, dragging = true, scrolling = false, density.density, motion, ::send)
                                send(AgentFrame.PointerButton(InputKeys.BUTTON_LEFT, pressed = false))
                            }
                            is Gesture.Slide -> track(
                                first.uptimeMillis, dragging = false, scrolling = start.fingers >= 2,
                                density.density, motion, ::send,
                            )
                            Gesture.Done -> Unit
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (remote != null) {
                RemoteScreenCanvas(
                    screenView,
                    zoom,
                    dimmed = status.phase != TabPhase.CONNECTED || pad.blocked,
                    showCursor = !hovering,
                    cursorMinScale = density.density,
                )
                // Over the image, only what keeps it from being live.
                if (status.phase != TabPhase.CONNECTED || !pad.ready || pad.blocked) {
                    Box(Modifier.background(TitanColors.TerminalBg.copy(alpha = 0.85f))) {
                        SurfaceMessage(status, pad, remote = true)
                    }
                }
            } else {
                SurfaceMessage(status, pad)
            }
        }
        LaunchedEffect(tab.id) { if (!isAndroidRuntime()) surfaceFocus.requestFocus() }

        Hairline()
        // A remote desktop with a mouse has its buttons already.
        if (remote == null || isAndroidRuntime()) {
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                listOf(
                    "izquierdo" to InputKeys.BUTTON_LEFT,
                    "central" to InputKeys.BUTTON_MIDDLE,
                    "derecho" to InputKeys.BUTTON_RIGHT,
                ).forEachIndexed { i, (label, button) ->
                    if (i > 0) Box(Modifier.width(TitanDimens.Hairline).fillMaxHeight().background(TitanColors.HairlineStrong))
                    MouseButton(
                        label,
                        onDown = { send(AgentFrame.PointerButton(button, pressed = true)) },
                        onUp = { send(AgentFrame.PointerButton(button, pressed = false)) },
                        modifier = Modifier.weight(if (button == InputKeys.BUTTON_MIDDLE) 0.6f else 1f).fillMaxSize(),
                    )
                }
            }
            Hairline()
        }

        val ime = WindowInsets.ime
        val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
        Column(Modifier.fillMaxWidth().background(TitanColors.Canvas)) {
            KeyBarRow(
                keys = keyBar,
                inTerminal = false,
                sticky = sticky,
                onKey = { key -> pressKey(key) },
                onConfigure = onConfigureKeyBar,
                keyboardVisible = keyboardVisible,
                onToggleKeyboard = if (isAndroidRuntime()) ({
                    if (keyboardVisible) {
                        keyboardController?.hide()
                    } else {
                        keyboardFocus.requestFocus()
                        keyboardController?.show()
                    }
                }) else null,
            )
            if (isAndroidRuntime()) {
                SoftKeyboardCapture(
                    focusRequester = keyboardFocus,
                    onText = { sendText(it) },
                    onEnter = { sendKey(InputKeys.ENTER) },
                    onBackspace = { sendKey(InputKeys.BACKSPACE) },
                )
            }
        }
    }
}

/** How a touch started, decided before it reaches the touch slop or the hold timeout. */
private sealed interface Gesture {
    /** Every finger lifted without moving: a click, with as many fingers as touched. */
    data class Tap(val fingers: Int) : Gesture

    /** One finger held still: the left button goes down, to drag. */
    data object Hold : Gesture

    /** The fingers moved: the pointer with one, scrolling with more. */
    data class Slide(val fingers: Int) : Gesture

    /** Several fingers held still and lifted: nothing to do. */
    data object Done : Gesture
}

private suspend fun AwaitPointerEventScope.decideGesture(slop: Float, holdMillis: Long): Gesture {
    var fingers = 1
    var travel = 0f
    val decided = withTimeoutOrNull(holdMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            fingers = maxOf(fingers, down.size)
            if (down.isEmpty()) return@withTimeoutOrNull Gesture.Tap(fingers)
            travel += averageChange(down).getDistance()
            event.changes.forEach { it.consume() }
            if (travel > slop) return@withTimeoutOrNull Gesture.Slide(fingers)
        }
        @Suppress("UNREACHABLE_CODE")
        Gesture.Done
    }
    if (decided != null) return decided
    if (fingers == 1) return Gesture.Hold
    // Several fingers held still: scroll if they start moving, else nothing.
    return Gesture.Slide(fingers)
}

/**
 * Follows the gesture until every finger lifts: the pointer while [dragging]
 * or with one finger, scrolling with two or more. Once scrolling, it stays so,
 * so lifting the fingers one by one does not nudge the pointer.
 */
private suspend fun AwaitPointerEventScope.track(
    startMillis: Long,
    dragging: Boolean,
    scrolling: Boolean,
    density: Float,
    motion: MousepadMotion,
    send: (AgentFrame) -> Unit,
) {
    var last = startMillis
    var scroll = scrolling && !dragging
    while (true) {
        val event = awaitPointerEvent()
        val down = event.changes.filter { it.pressed }
        if (down.isEmpty()) return
        val now = down.first().uptimeMillis
        val elapsed = (now - last).coerceAtLeast(1)
        last = now
        if (!dragging && down.size >= 2) scroll = true
        val delta = averageChange(down) / density
        val frame = if (scroll) motion.scroll(delta.x, delta.y) else motion.move(delta.x, delta.y, elapsed)
        frame?.let(send)
        event.changes.forEach { it.consume() }
    }
}

private fun averageChange(changes: List<PointerInputChange>): Offset {
    if (changes.isEmpty()) return Offset.Zero
    var sum = Offset.Zero
    changes.forEach { sum += it.positionChange() }
    return sum / changes.size.toFloat()
}

/**
 * The phase, and a warning while the desktop is out of reach. Tapping the text
 * unfolds the technical cause behind it, when there is one ([onToggleCause]).
 */
@Composable
private fun MousepadStatusStrip(
    status: TabStatus,
    pad: MousepadStatus,
    onReconnect: () -> Unit,
    causeOpen: Boolean,
    onToggleCause: (() -> Unit)?,
    zoom: Int? = null,
    onZoom: () -> Unit = {},
) {
    val (marker, color, label) = when {
        status.phase == TabPhase.CONNECTED && pad.blocked -> Triple("[!]", TitanColors.Warning, "Escritorio bloqueado")
        status.phase == TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "Conectado al escritorio")
        status.phase == TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        status.phase == TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
        status.phase == TabPhase.PAUSED -> Triple("[-]", TitanColors.Mute, "En pausa")
        status.phase == TabPhase.DISCONNECTED -> Triple("[x]", TitanColors.Danger, "Caída")
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
        if (zoom != null) {
            Text(
                "zoom ${zoom}x",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Accent,
                modifier = Modifier.clickable(onClick = onZoom).padding(horizontal = TitanDimens.SpaceXs),
            )
        }
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

/**
 * What the surface says: the gestures, or why input does not reach the
 * desktop. Over a [remote] screen, a drop says the image is the last one seen.
 */
@Composable
private fun SurfaceMessage(status: TabStatus, pad: MousepadStatus, remote: Boolean = false) {
    val (text, color) = when {
        status.phase == TabPhase.FAILED || status.phase == TabPhase.DISCONNECTED ->
            (status.detail ?: "Sin conexión con el destino.") to TitanColors.Danger
        remote && status.phase == TabPhase.RECONNECTING ->
            "Reconectando… La imagen es la última que llegó." to TitanColors.Warning
        status.phase != TabPhase.CONNECTED || !pad.ready ->
            "Preparando el escritorio del destino…" to TitanColors.Mute
        pad.blocked && remote ->
            ("El PC está bloqueado o muestra un aviso de seguridad (UAC). El escritorio remoto no ve esa " +
                "pantalla: desbloquéalo en el PC. La imagen es la última que llegó.") to TitanColors.Warning
        pad.blocked ->
            ("El PC está bloqueado o muestra un aviso de seguridad (UAC). El mouse pad no llega a esa " +
                "pantalla: desbloquéalo en el PC.") to TitanColors.Warning
        else ->
            ("Un dedo: mover · toca: clic\nDos dedos: scroll · toca con dos: clic derecho\n" +
                "Mantén pulsado: arrastrar") to TitanColors.Stone
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(TitanDimens.SpaceXl),
    )
}

/** The touch zoom steps of a remote desktop: 1x (whole screen) to this. */
private const val MAX_ZOOM = 3

/** How [view]'s screen fits a surface of [width]×[height] at [zoom], following the cursor. */
private fun screenViewport(view: ScreenView, width: Float, height: Float, zoom: Int): ScreenViewport? =
    view.surface?.let { s ->
        ScreenViewport(
            width, height, s.width, s.height,
            zoom = zoom.toFloat(), focusX = view.cursorX.toFloat(), focusY = view.cursorY.toFloat(),
        )
    }

/**
 * The remote screen (ADR-0029) fitted into the surface at [zoom], with the
 * remote cursor on top when [showCursor]. The cursor is drawn at least
 * [cursorMinScale] view pixels per screen pixel, so it stays visible on a
 * phone that shows a large screen small. [dimmed] while not connected: it is
 * the last image seen, not the live one. [screen] is read while drawing, so a
 * new frame or cursor position only redraws.
 */
@Composable
private fun RemoteScreenCanvas(
    screen: State<ScreenView>,
    zoom: Int,
    dimmed: Boolean,
    showCursor: Boolean,
    cursorMinScale: Float,
) {
    Canvas(Modifier.fillMaxSize()) {
        val view = screen.value
        val surface = view.surface ?: return@Canvas
        val vp = screenViewport(view, size.width, size.height, zoom) ?: return@Canvas
        if (vp.scale <= 0f) return@Canvas
        drawScreenSurface(
            surface,
            vp.offsetX,
            vp.offsetY,
            vp.scale,
            alpha = if (dimmed) 0.45f else 1f,
            filterQuality = if (vp.scale < 1f) FilterQuality.Medium else FilterQuality.Low,
        )
        val cursor = view.cursor
        if (showCursor && view.cursorVisible && cursor != null) {
            val s = maxOf(vp.scale, cursorMinScale)
            val x = vp.offsetX + view.cursorX * vp.scale - cursor.hotX * s
            val y = vp.offsetY + view.cursorY * vp.scale - cursor.hotY * s
            drawScreenSurface(cursor.surface, x.roundToInt().toFloat(), y.roundToInt().toFloat(), s)
        }
    }
}

/**
 * A mouse over a remote screen (ADR-0029): moves put the remote pointer under
 * it ([AgentFrame.PointerTo]), its buttons and wheel go as they are. Touch is
 * left to the touchpad gestures.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mouseOnScreen(
    viewport: () -> ScreenViewport?,
    onHover: (Boolean) -> Unit,
    onPress: () -> Unit,
    send: (AgentFrame) -> Unit,
) = awaitPointerEventScope {
    var buttons = 0
    var lastX = -1
    var lastY = -1
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        if (change.type != PointerType.Mouse) continue
        when (event.type) {
            PointerEventType.Enter -> onHover(true)
            PointerEventType.Exit -> {
                onHover(false)
                continue
            }
        }
        val vp = viewport() ?: continue
        if (event.type == PointerEventType.Scroll) {
            val d = change.scrollDelta
            val dx = (d.x * WHEEL_NOTCH).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val dy = (-d.y * WHEEL_NOTCH).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            if (dx != 0 || dy != 0) send(AgentFrame.Scroll(dx, dy))
            change.consume()
            continue
        }
        onHover(true)
        val (x, y) = vp.toScreen(change.position.x, change.position.y)
        if (x != lastX || y != lastY) {
            send(AgentFrame.PointerTo(x, y))
            lastX = x
            lastY = y
        }
        val now = (if (event.buttons.isPrimaryPressed) 1 else 0) or
            (if (event.buttons.isSecondaryPressed) 2 else 0) or
            (if (event.buttons.isTertiaryPressed) 4 else 0)
        if (now and buttons.inv() != 0) onPress()
        for ((bit, button) in MOUSE_BUTTONS) {
            if ((now xor buttons) and bit != 0) send(AgentFrame.PointerButton(button, pressed = now and bit != 0))
        }
        buttons = now
        event.changes.forEach { it.consume() }
    }
}

/** A wheel notch in the protocol's units. */
private const val WHEEL_NOTCH = 120f

private val MOUSE_BUTTONS = listOf(
    1 to InputKeys.BUTTON_LEFT,
    2 to InputKeys.BUTTON_RIGHT,
    4 to InputKeys.BUTTON_MIDDLE,
)

/** A mouse button: down while the finger is on it, so it can be held to drag. */
@Composable
private fun MouseButton(label: String, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(if (pressed) TitanColors.Accent.copy(alpha = 0.15f) else TitanColors.Surface)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    onDown()
                    tryAwaitRelease()
                    onUp()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (pressed) TitanColors.Accent else TitanColors.Body)
    }
}

/**
 * A physical keyboard (on the desktop app, or plugged into the phone) typing
 * on the mouse pad: its keys, with the modifiers it holds. Null for events it
 * does not handle.
 */
private fun hardwareKeyFrames(event: KeyEvent): List<AgentFrame>? {
    if (event.type != KeyEventType.KeyDown) return null
    var mods = 0
    if (event.isCtrlPressed) mods = mods or InputKeys.MOD_CTRL
    if (event.isAltPressed) mods = mods or InputKeys.MOD_ALT
    if (event.isMetaPressed) mods = mods or InputKeys.MOD_META
    HARDWARE_KEYS[event.key]?.let { key ->
        if (event.isShiftPressed) mods = mods or InputKeys.MOD_SHIFT
        return listOf(AgentFrame.Key(key, mods))
    }
    var codePoint = event.utf16CodePoint
    // Some platforms report Ctrl+letter as its control character (Ctrl+C = 3).
    if (event.isCtrlPressed && codePoint in 1..26) codePoint += 'a'.code - 1
    if (codePoint == 0 || codePoint < 0x20) return null
    val text = buildString { appendCodePointCompat(codePoint) }
    return MousepadTyping.typed(text, mods)
}

private fun StringBuilder.appendCodePointCompat(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val v = codePoint - 0x10000
        append((0xD800 + (v shr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
}

private val HARDWARE_KEYS: Map<Key, Int> = mapOf(
    Key.Enter to InputKeys.ENTER,
    Key.NumPadEnter to InputKeys.ENTER,
    Key.Escape to InputKeys.ESCAPE,
    Key.Backspace to InputKeys.BACKSPACE,
    Key.Tab to InputKeys.TAB,
    Key.Delete to InputKeys.DELETE,
    Key.Insert to InputKeys.INSERT,
    Key.MoveHome to InputKeys.HOME,
    Key.MoveEnd to InputKeys.END,
    Key.PageUp to InputKeys.PAGE_UP,
    Key.PageDown to InputKeys.PAGE_DOWN,
    Key.DirectionLeft to InputKeys.ARROW_LEFT,
    Key.DirectionRight to InputKeys.ARROW_RIGHT,
    Key.DirectionUp to InputKeys.ARROW_UP,
    Key.DirectionDown to InputKeys.ARROW_DOWN,
    Key.F1 to InputKeys.function(1),
    Key.F2 to InputKeys.function(2),
    Key.F3 to InputKeys.function(3),
    Key.F4 to InputKeys.function(4),
    Key.F5 to InputKeys.function(5),
    Key.F6 to InputKeys.function(6),
    Key.F7 to InputKeys.function(7),
    Key.F8 to InputKeys.function(8),
    Key.F9 to InputKeys.function(9),
    Key.F10 to InputKeys.function(10),
    Key.F11 to InputKeys.function(11),
    Key.F12 to InputKeys.function(12),
)
