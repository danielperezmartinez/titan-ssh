package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielperezmartinez.titanssh.terminal.AgentFrame
import io.github.danielperezmartinez.titanssh.terminal.InputKeys
import io.github.danielperezmartinez.titanssh.terminal.MousepadMotion
import io.github.danielperezmartinez.titanssh.terminal.MousepadStatus
import io.github.danielperezmartinez.titanssh.terminal.MousepadTyping
import io.github.danielperezmartinez.titanssh.terminal.SessionTab
import io.github.danielperezmartinez.titanssh.terminal.TabPhase
import io.github.danielperezmartinez.titanssh.terminal.TabStatus
import io.github.danielperezmartinez.titanssh.terminal.isAndroidRuntime
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * A mouse pad tab (ADR-0016): the phone as the destination's touchpad and
 * keyboard. The surface takes the gestures of a laptop touchpad:
 *
 * - one finger moves the pointer, and a tap is a left click;
 * - two fingers scroll, and a two-finger tap is a right click (three, middle);
 * - holding a finger still presses the left button, to drag until it lifts.
 *
 * Below it, buttons to hold as a mouse's, sticky Ctrl/Alt/Shift/Win, the keys a
 * phone keyboard lacks, and the system keyboard. Events go out through
 * [SessionTab.sendInput]; the strip on top says whether they reach the desktop.
 */
@Composable
fun MousepadView(tab: SessionTab, modifier: Modifier = Modifier) {
    val status by tab.status.collectAsState()
    val pad by tab.mousepad.collectAsState()
    val pendingHostKey by tab.pendingHostKey.collectAsState()
    val motion = remember(tab.id) { MousepadMotion(tab.resolved.session.mousepad) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }

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
        MousepadStatusStrip(status, pad, onReconnect = { tab.reconnectNow() })
        Hairline()

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(surfaceFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    val frames = hardwareKeyFrames(event) ?: return@onPreviewKeyEvent false
                    frames.forEach(::send)
                    true
                }
                .pointerInput(tab.id) {
                    val slop = viewConfiguration.touchSlop
                    val holdMillis = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
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
            SurfaceMessage(status, pad)
        }
        LaunchedEffect(tab.id) { if (!isAndroidRuntime()) surfaceFocus.requestFocus() }

        Hairline()
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

        val ime = WindowInsets.ime
        val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
        Column(Modifier.fillMaxWidth().background(TitanColors.Canvas)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceXs, vertical = TitanDimens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MODIFIERS.forEach { (label, mod) ->
                        AccessoryButton(label, active = sticky and mod != 0) { toggleModifier(mod) }
                    }
                    KEYS.forEach { (label, key) -> AccessoryButton(label, active = false) { sendKey(key) } }
                }
                if (isAndroidRuntime()) {
                    Spacer(Modifier.width(TitanDimens.SpaceXs))
                    AccessoryButton("[kbd]", active = keyboardVisible) {
                        if (keyboardVisible) {
                            keyboardController?.hide()
                        } else {
                            keyboardFocus.requestFocus()
                            keyboardController?.show()
                        }
                    }
                }
            }
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

/** The phase, and a warning while the desktop is out of reach. */
@Composable
private fun MousepadStatusStrip(status: TabStatus, pad: MousepadStatus, onReconnect: () -> Unit) {
    val (marker, color, label) = when {
        status.phase == TabPhase.CONNECTED && pad.blocked -> Triple("[!]", TitanColors.Warning, "Escritorio bloqueado")
        status.phase == TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "Conectado al escritorio")
        status.phase == TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        status.phase == TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
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
            color = TitanColors.Mute,
            maxLines = 1,
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
    }
}

/** What the surface says: the gestures, or why input does not reach the desktop. */
@Composable
private fun SurfaceMessage(status: TabStatus, pad: MousepadStatus) {
    val (text, color) = when {
        status.phase == TabPhase.FAILED || status.phase == TabPhase.DISCONNECTED ->
            (status.detail ?: "Sin conexión con el destino.") to TitanColors.Danger
        status.phase != TabPhase.CONNECTED || !pad.ready ->
            "Preparando el escritorio del destino…" to TitanColors.Mute
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

private val MODIFIERS = listOf(
    "Ctrl" to InputKeys.MOD_CTRL,
    "Alt" to InputKeys.MOD_ALT,
    "Mayús" to InputKeys.MOD_SHIFT,
    "Win" to InputKeys.MOD_META,
)

private val KEYS = listOf(
    "Esc" to InputKeys.ESCAPE,
    "Tab" to InputKeys.TAB,
    "←" to InputKeys.ARROW_LEFT,
    "↑" to InputKeys.ARROW_UP,
    "↓" to InputKeys.ARROW_DOWN,
    "→" to InputKeys.ARROW_RIGHT,
    "Supr" to InputKeys.DELETE,
    "Inicio" to InputKeys.HOME,
    "Fin" to InputKeys.END,
    "RePág" to InputKeys.PAGE_UP,
    "AvPág" to InputKeys.PAGE_DOWN,
    "Menú" to InputKeys.CONTEXT_MENU,
    "ImpPnt" to InputKeys.PRINT_SCREEN,
) + (1..12).map { "F$it" to InputKeys.function(it) } + listOf(
    "Vol-" to InputKeys.VOLUME_DOWN,
    "Vol+" to InputKeys.VOLUME_UP,
    "Silencio" to InputKeys.VOLUME_MUTE,
    "Play/Pausa" to InputKeys.MEDIA_PLAY_PAUSE,
    "Anterior" to InputKeys.MEDIA_PREVIOUS,
    "Siguiente" to InputKeys.MEDIA_NEXT,
)

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
