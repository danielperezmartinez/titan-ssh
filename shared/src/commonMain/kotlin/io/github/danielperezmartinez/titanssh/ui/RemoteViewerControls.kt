package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import io.github.danielperezmartinez.titanssh.terminal.ConnectionQuality
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.danielperezmartinez.titanssh.config.MAX_DESKTOP_ZOOM
import io.github.danielperezmartinez.titanssh.terminal.ScreenView
import io.github.danielperezmartinez.titanssh.terminal.ScreenViewport
import io.github.danielperezmartinez.titanssh.terminal.drawScreenSurface
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the remote desktop viewer shows the screen and what its controls do
 * ([[Controles del visor del escritorio remoto con barra movible]]): fitted or 1:1, the zoom
 * over that, and the actions beside them. [percent] is the scale on screen,
 * null before the first frame. [minimap] says whether the minimap shows, null
 * while the whole screen fits and there is none to show.
 */
internal class ViewerControls(
    val actualSize: Boolean,
    val zoom: Float,
    val percent: Int?,
    val fullscreen: Boolean,
    val minimap: Boolean?,
    val onMinimap: (Boolean) -> Unit,
    val onActualSize: (Boolean) -> Unit,
    val onZoom: (Float) -> Unit,
    val onCtrlAltDel: () -> Unit,
    val onFullscreen: (Boolean) -> Unit,
) {
    /** The next zoom step up from the current zoom, or null at the top. */
    fun zoomIn(): Float? = DESKTOP_ZOOM_STEPS.firstOrNull { it > zoom + 0.01f }

    /** The next zoom step down, or null at 1. */
    fun zoomOut(): Float? = DESKTOP_ZOOM_STEPS.lastOrNull { it < zoom - 0.01f }

    /** `ajustar 64%`, `1:1 100%`, or `2x` on a phone, for the strip's [#] vista. */
    fun summary(phone: Boolean): String =
        if (phone) zoomLabel(zoom) else (if (actualSize) "1:1" else "ajustar") + (percent?.let { " $it%" } ?: "")
}

/** The zoom steps of the desktop's − and +. */
internal val DESKTOP_ZOOM_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 3f, MAX_DESKTOP_ZOOM)

/** The zoom levels of the phone's panel; pinching reaches the ones between. */
private val PHONE_ZOOM_LEVELS = listOf(1f, 2f, 3f, MAX_DESKTOP_ZOOM)

private fun zoomLabel(zoom: Float): String {
    val rounded = (zoom * 10).roundToInt() / 10f
    return (if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()) + "x"
}

/**
 * The viewer's controls in a row: in the desktop's status strip and in the
 * full screen's floating bar ([[Franja de estado según la plataforma]]).
 */
@Composable
internal fun ViewerStripControls(controls: ViewerControls, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm), verticalAlignment = Alignment.CenterVertically) {
        Row {
            StripChip("ajustar", selected = !controls.actualSize, onClick = { controls.onActualSize(false) }, joined = JoinedSide.START)
            StripChip("1:1", selected = controls.actualSize, onClick = { controls.onActualSize(true) }, joined = JoinedSide.END)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StripText("−", enabled = controls.zoomOut() != null, onClick = { controls.zoomOut()?.let(controls.onZoom) })
            Text(
                controls.percent?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Ink,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = TextAlign.Center,
            )
            StripText("+", enabled = controls.zoomIn() != null, onClick = { controls.zoomIn()?.let(controls.onZoom) })
        }
        controls.minimap?.let { shown ->
            StripChip(if (shown) "[x] minimapa" else "[ ] minimapa", selected = false, onClick = { controls.onMinimap(!shown) })
        }
        StripChip("Ctrl+Alt+Supr", selected = false, onClick = controls.onCtrlAltDel)
        StripChip(
            if (controls.fullscreen) "[x] salir de completa" else "[ ] completa",
            selected = false,
            accent = true,
            onClick = { controls.onFullscreen(!controls.fullscreen) },
        )
    }
}

/**
 * The phone's `[#] vista` panel: the same controls as the desktop's strip, in
 * groups that wrap on a narrow screen, with touch-sized targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewerPanel(controls: ViewerControls, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(TitanColors.Surface)
            .padding(TitanDimens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceMd),
    ) {
        TitanSegmented(
            label = "zoom · también pellizcando",
            options = PHONE_ZOOM_LEVELS,
            selected = PHONE_ZOOM_LEVELS.firstOrNull { abs(it - controls.zoom) < 0.05f } ?: -1f,
            onSelect = controls.onZoom,
            optionLabel = ::zoomLabel,
        )
        TitanSegmented(
            label = "escala",
            options = listOf(false, true),
            selected = controls.actualSize,
            onSelect = controls.onActualSize,
            optionLabel = { if (it) "1:1" else "ajustar" },
        )
        controls.minimap?.let { shown -> TitanCheck("minimapa", checked = shown, onCheckedChange = controls.onMinimap) }
        Hairline()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
        ) {
            TitanButton("Ctrl+Alt+Supr", onClick = controls.onCtrlAltDel)
            TitanButton(
                if (controls.fullscreen) "[x] salir de pantalla completa" else "[ ] pantalla completa",
                onClick = { controls.onFullscreen(!controls.fullscreen) },
                kind = ButtonKind.PRIMARY,
            )
        }
    }
}

/**
 * Where the visible part of a cropped screen is: the whole screen small, and
 * the view's rectangle over it. [screen] is read while drawing.
 */
@Composable
internal fun ScreenMinimap(
    screen: State<ScreenView>,
    viewport: () -> ScreenViewport?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = screen.value.surface ?: return
    Box(modifier) {
        MinimapImage(screen, viewport, surface.width.toFloat() / surface.height.coerceAtLeast(1))
        // Hidden here, it comes back from the controls' [ ] minimapa.
        Text(
            "[x]",
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.Ink,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(TitanColors.Canvas.copy(alpha = 0.85f))
                .clickable(onClick = onHide)
                .padding(horizontal = TitanDimens.SpaceXs),
        )
    }
}

@Composable
private fun MinimapImage(screen: State<ScreenView>, viewport: () -> ScreenViewport?, ratio: Float) {
    Canvas(
        Modifier
            .width(160.dp)
            .aspectRatio(ratio)
            .border(TitanDimens.Hairline, TitanColors.HairlineStrong),
    ) {
        val s = screen.value.surface ?: return@Canvas
        val vp = viewport() ?: return@Canvas
        val k = size.width / s.width
        drawScreenSurface(s, 0f, 0f, k, alpha = 0.85f, filterQuality = FilterQuality.Medium)
        if (vp.scale <= 0f) return@Canvas
        val left = (-vp.offsetX / vp.scale).coerceAtLeast(0f) * k
        val top = (-vp.offsetY / vp.scale).coerceAtLeast(0f) * k
        val w = (vp.viewW / vp.scale).coerceAtMost(s.width.toFloat()) * k
        val h = (vp.viewH / vp.scale).coerceAtMost(s.height.toFloat()) * k
        drawRect(TitanColors.Accent, Offset(left, top), Size(w, h), style = Stroke(width = 2.dp.toPx()))
    }
}

internal enum class JoinedSide { START, END }

/** A compact option or action of the strip; [joined] glues two into one segmented pair. */
@Composable
internal fun StripChip(label: String, selected: Boolean, onClick: () -> Unit, accent: Boolean = false, joined: JoinedSide? = null) {
    val r = TitanDimens.RadiusSm
    val shape = when (joined) {
        JoinedSide.START -> RoundedCornerShape(topStart = r, bottomStart = r)
        JoinedSide.END -> RoundedCornerShape(topEnd = r, bottomEnd = r)
        null -> RoundedCornerShape(r)
    }
    Box(
        Modifier
            .border(TitanDimens.Hairline, if (accent) TitanColors.Accent else TitanColors.HairlineStrong, shape)
            .background(if (selected) TitanColors.Accent.copy(alpha = 0.15f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = TitanDimens.SpaceSm, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                accent -> TitanColors.Accent
                selected -> TitanColors.Ink
                else -> TitanColors.Mute
            },
        )
    }
}

@Composable
private fun StripText(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) TitanColors.Body else TitanColors.Stone,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = TitanDimens.SpaceXs),
    )
}

/**
 * The desktop's full screen bar: one element that folds and unfolds in place.
 * Folded it shows the connection and the session; a mouse on it, or a click,
 * unfolds the strip's controls, and it folds again when the mouse leaves
 * unless pinned. The user drags it anywhere by its handle, so it never covers
 * something for good: [at] is where, as 0..1 of the room left around it, so
 * it unfolds towards the side with room. Folded, it [hides] if the user chose
 * so: then a mouse at the middle of the nearest screen edge brings it back.
 */
@Composable
internal fun FullscreenBar(
    controls: ViewerControls,
    title: String,
    quality: ConnectionQuality,
    live: Boolean,
    at: Offset,
    onMove: (Offset) -> Unit,
    onMoved: () -> Unit,
    hides: Boolean,
    onHides: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    // Shown from the edge band, the mouse gets longer to reach it than to leave it.
    var visited by remember { mutableStateOf(false) }
    LaunchedEffect(open, inside, pinned, dragging) {
        if (inside) visited = true
        if (open && !inside && !pinned && !dragging) {
            delay(if (visited) BAR_FOLD_MILLIS else BAR_REACH_MILLIS)
            open = false
            visited = false
        }
    }
    val latestAt by rememberUpdatedState(at)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnMoved by rememberUpdatedState(onMoved)
    val unfolded = open || pinned
    val atBottom = at.y > 0.5f
    BoxWithConstraints(modifier) {
        val roomX = (constraints.maxWidth - barSize.width).coerceAtLeast(0)
        val roomY = (constraints.maxHeight - barSize.height).coerceAtLeast(0)
        val room by rememberUpdatedState(IntSize(roomX, roomY))
        if (hides && !unfolded) {
            // Gone: only a band in the middle of the nearest edge, under where it was.
            Box(
                Modifier
                    .align(if (atBottom) Alignment.BottomStart else Alignment.TopStart)
                    .offset {
                        val band = constraints.maxWidth * 0.4f
                        val centre = at.x * roomX + barSize.width / 2f
                        IntOffset((centre - band / 2f).coerceIn(0f, constraints.maxWidth - band).roundToInt(), 0)
                    }
                    .fillMaxWidth(0.4f)
                    .height(3.dp)
                    .onHoverChange { if (it) open = true },
            )
            return@BoxWithConstraints
        }
        val shape = RoundedCornerShape(TitanDimens.RadiusSm)
        Row(
            Modifier
                .offset { IntOffset((at.x * roomX).roundToInt(), (at.y * roomY).roundToInt()) }
                .onSizeChanged { barSize = it }
                .onHoverChange {
                    inside = it
                    if (it) open = true
                }
                .background(TitanColors.Canvas.copy(alpha = 0.94f), shape)
                .border(TitanDimens.Hairline, TitanColors.HairlineStrong, shape)
                .clickable(enabled = !unfolded) { open = true }
                .animateContentSize()
                .padding(end = TitanDimens.SpaceMd),
            horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⋮⋮",
                style = MaterialTheme.typography.labelSmall,
                color = TitanColors.Mute,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                latestOnMoved()
                            },
                            onDragCancel = {
                                dragging = false
                                latestOnMoved()
                            },
                        ) { change, delta ->
                            change.consume()
                            val current = latestAt
                            latestOnMove(
                                Offset(
                                    if (room.width > 0) (current.x + delta.x / room.width).coerceIn(0f, 1f) else current.x,
                                    if (room.height > 0) (current.y + delta.y / room.height).coerceIn(0f, 1f) else current.y,
                                ),
                            )
                        }
                    }
                    .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceXs),
            )
            if (live) {
                ConnectionQualityBar(quality)
            } else {
                Text("[-]", style = MaterialTheme.typography.labelSmall, color = TitanColors.Warning)
            }
            Text(title, style = MaterialTheme.typography.labelSmall, color = TitanColors.Ink, maxLines = 1)
            if (unfolded) {
                ViewerStripControls(controls)
                StripChip(if (pinned) "[x] fijar" else "[ ] fijar", selected = false, onClick = { pinned = !pinned })
                StripChip(if (hides) "[x] ocultar" else "[ ] ocultar", selected = false, onClick = { onHides(!hides) })
            } else {
                Text("▸", style = MaterialTheme.typography.labelSmall, color = TitanColors.Mute)
            }
        }
    }
}

/** On entering the desktop's full screen, for a few seconds: how to get out of it. */
@Composable
internal fun FullscreenHint(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MILLIS)
        visible = false
    }
    if (!visible) return
    Text(
        "Pantalla completa · Ctrl+Alt+Intro para salir · lleva el ratón arriba al centro para ver la barra",
        style = MaterialTheme.typography.labelSmall,
        color = TitanColors.Ink,
        modifier = modifier
            .background(TitanColors.Canvas.copy(alpha = 0.94f), RoundedCornerShape(TitanDimens.RadiusSm))
            .border(TitanDimens.Hairline, TitanColors.HairlineStrong, RoundedCornerShape(TitanDimens.RadiusSm))
            .padding(horizontal = TitanDimens.SpaceLg, vertical = TitanDimens.SpaceSm),
    )
}

/** A [GlyphButton] floating over the remote screen, readable on any image. */
@Composable
internal fun FloatingGlyph(glyph: String, onClick: () -> Unit) {
    GlyphButton(
        glyph,
        onClick = onClick,
        color = TitanColors.Ink,
        modifier = Modifier
            .background(TitanColors.Canvas.copy(alpha = 0.85f), RoundedCornerShape(TitanDimens.RadiusSm))
            .border(TitanDimens.Hairline, TitanColors.HairlineStrong, RoundedCornerShape(TitanDimens.RadiusSm)),
    )
}

/** Reports a mouse entering ([onChange] true) and leaving this element. */
private fun Modifier.onHoverChange(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> onChange(true)
                PointerEventType.Exit -> onChange(false)
                else -> Unit
            }
        }
    }
}

private const val BAR_FOLD_MILLIS = 700L
private const val BAR_REACH_MILLIS = 2500L
private const val HINT_MILLIS = 4000L
