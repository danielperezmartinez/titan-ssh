package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.terminal.KeyBar
import io.github.danielperezmartinez.titanssh.terminal.KeyBarAction
import io.github.danielperezmartinez.titanssh.terminal.KeyBarGroup
import io.github.danielperezmartinez.titanssh.terminal.KeyBarKey
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlin.math.roundToInt

/**
 * The bar above the keyboard, shared by the terminal and the mouse pad: the
 * user's [keys] scroll sideways, and `[*]` (opens [KeyBarEditor]), `[abc]`
 * (flips the keyboard's [suggestions], when [onToggleSuggestions] is given) and
 * `[kbd]` (raises or hides the system keyboard, when [onToggleKeyboard] is
 * given) stay pinned on the right. A modifier lights up while it is in [sticky]; in the
 * terminal ([inTerminal]) the keys only a remote desktop has are dimmed and
 * inert. What a key does is the caller's: [onKey].
 */
@Composable
internal fun KeyBarRow(
    keys: List<KeyBarKey>,
    inTerminal: Boolean,
    sticky: Int,
    onKey: (KeyBarKey) -> Unit,
    onConfigure: () -> Unit,
    keyboardVisible: Boolean,
    onToggleKeyboard: (() -> Unit)?,
    suggestions: Boolean = false,
    onToggleSuggestions: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanDimens.SpaceXs, vertical = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            keys.forEach { key ->
                val action = key.action
                AccessoryButton(
                    key.label,
                    active = action is KeyBarAction.Modifier && sticky and action.mod != 0,
                    enabled = !inTerminal || key.inTerminal,
                ) { onKey(key) }
            }
        }
        Spacer(Modifier.width(TitanDimens.SpaceXs))
        AccessoryButton("[*]", active = false, onClick = onConfigure)
        if (onToggleSuggestions != null) {
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            AccessoryButton("[abc]", active = suggestions, onClick = onToggleSuggestions)
        }
        if (onToggleKeyboard != null) {
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            AccessoryButton("[kbd]", active = keyboardVisible, onClick = onToggleKeyboard)
        }
    }
}

@Composable
internal fun AccessoryButton(label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) TitanColors.Accent.copy(alpha = 0.15f) else TitanColors.Surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> TitanColors.Stone
                active -> TitanColors.Accent
                else -> TitanColors.Body
            },
        )
    }
}

/** A key being dragged in [KeyBarEditor]: where the finger is, in root coordinates. */
private data class KeyDrag(
    val key: KeyBarKey,
    val fromBar: Boolean,
    /** The finger's offset inside the chip, so the chip does not jump sideways. */
    val grab: Offset,
    val chipHeight: Float,
    val pointer: Offset,
    /** The bar's other chips as they were when the drag began, in bar order. */
    val others: List<Rect>,
)

/**
 * "Barra de teclas": the bar on top, as the terminal and the mouse pad show it
 * (wrapped, so every key is in sight), and the whole catalog below by group.
 * Holding a key and dragging it adds it to the bar where it is dropped (from
 * the catalog), moves it (inside the bar) or removes it (out of the bar). A
 * tap on a catalog key appends it. Each change is saved at once.
 */
@Composable
fun KeyBarEditor(controller: ConfigController, onBack: () -> Unit) {
    val config by controller.state.collectAsState()
    val saved = config.settings.keyBar
    val bar = remember(saved) { KeyBar.resolve(saved) }
    val barIds = bar.map { it.id }
    // The gestures outlive compositions and may hold callbacks from an earlier
    // one: they read the bar through this, never a captured copy, or a drop
    // would rebuild the bar from before the last change.
    val currentBar by rememberUpdatedState(bar)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val liftPx = with(density) { TitanDimens.SpaceLg.toPx() }

    var drag by remember { mutableStateOf<KeyDrag?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var barZone by remember { mutableStateOf(Rect.Zero) }
    val barRects = remember { mutableStateMapOf<String, Rect>() }

    val current = drag
    val overBar = current != null && current.pointer.y <= barZone.bottom
    val removing = current != null && current.fromBar && !overBar

    fun startDrag(key: KeyBarKey, fromBar: Boolean, chip: Rect, finger: Offset) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val others = currentBar.filter { !(fromBar && it.id == key.id) }.mapNotNull { barRects[it.id] }
        drag = KeyDrag(key, fromBar, grab = finger, chipHeight = chip.height, pointer = chip.topLeft + finger, others = others)
    }

    fun endDrag() {
        val d = drag ?: return
        drag = null
        val others = currentBar.map { it.id }.filterNot { d.fromBar && it == d.key.id }
        when {
            d.pointer.y <= barZone.bottom -> {
                val at = insertionIndex(d.others, d.pointer).coerceIn(0, others.size)
                controller.setKeyBar(others.toMutableList().apply { add(at, d.key.id) })
            }
            d.fromBar -> controller.setKeyBar(others)
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
                Text(
                    "Barra de teclas",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TitanColors.Ink,
                    modifier = Modifier.weight(1f).padding(start = TitanDimens.SpaceSm),
                )
                TitanButton(
                    "Restablecer",
                    onClick = { controller.setKeyBar(null) },
                    kind = ButtonKind.SECONDARY,
                    enabled = saved != null,
                )
            }
            Hairline()

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (overBar) TitanColors.Accent.copy(alpha = 0.06f) else TitanColors.Canvas)
                    .onGloballyPositioned { barZone = it.boundsInRoot() }
                    .padding(bodyPadding()),
            ) {
                Caption("TU BARRA · mantén pulsada una tecla para moverla; arrástrala abajo para quitarla")
                Spacer(Modifier.height(TitanDimens.SpaceSm))
                if (bar.isEmpty()) {
                    Caption("Está vacía: arrastra aquí las teclas que quieras.", color = TitanColors.Stone)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                    verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                ) {
                    bar.forEach { key ->
                        EditorChip(
                            key,
                            ghost = current?.fromBar == true && current.key.id == key.id,
                            onRect = { barRects[key.id] = it },
                            onTap = null,
                            onDragStart = { chip, finger -> startDrag(key, fromBar = true, chip, finger) },
                            onDrag = { amount -> drag = drag?.let { it.copy(pointer = it.pointer + amount) } },
                            onDragEnd = { endDrag() },
                            onDragCancel = { drag = null },
                        )
                    }
                }
                Spacer(Modifier.height(TitanDimens.SpaceSm))
                Caption(
                    "Sobre el teclado la barra se desplaza de lado, con [*] y [kbd] siempre a la derecha.",
                    color = TitanColors.Stone,
                )
            }
            Hairline()

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (removing) TitanColors.Danger.copy(alpha = 0.08f) else TitanColors.Canvas)
                    .verticalScroll(rememberScrollState())
                    .padding(bodyPadding()),
            ) {
                Caption(
                    if (removing) "Suelta aquí para quitarla de la barra"
                    else "DISPONIBLES · arrastra una tecla a la barra, o tócala para añadirla al final",
                    color = if (removing) TitanColors.Danger else TitanColors.Mute,
                )
                KeyBarGroup.entries.forEach { group ->
                    val keys = KeyBar.catalog.filter { it.group == group }
                    Spacer(Modifier.height(TitanDimens.SpaceMd))
                    Text(group.title, style = MaterialTheme.typography.bodyMedium, color = TitanColors.Ink)
                    if (group == KeyBarGroup.DESKTOP) {
                        Caption("Solo hacen algo en el mouse pad; en la terminal salen atenuadas, como Win.")
                    }
                    Spacer(Modifier.height(TitanDimens.SpaceXs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                        verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs),
                    ) {
                        keys.forEach { key ->
                            val inBar = key.id in barIds
                            EditorChip(
                                key,
                                dimmed = inBar,
                                onTap = if (inBar) null else ({ controller.setKeyBar(currentBar.map { it.id } + key.id) }),
                                onDragStart = if (inBar) null else ({ chip, finger ->
                                    startDrag(key, fromBar = false, chip, finger)
                                }),
                                onDrag = { amount -> drag = drag?.let { it.copy(pointer = it.pointer + amount) } },
                                onDragEnd = { endDrag() },
                                onDragCancel = { drag = null },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(TitanDimens.SpaceMd))
                Caption("Las atenuadas ya están en la barra.", color = TitanColors.Stone)
            }
        }

        // Where the key lands, and the key itself under the finger.
        if (current != null) {
            if (overBar) {
                val line = insertionLine(current.others, insertionIndex(current.others, current.pointer), barZone)
                with(density) {
                    Box(
                        Modifier
                            .zIndex(1f)
                            .offset { (line.topLeft - rootOrigin).round() }
                            .size(2f.toDp(), line.height.toDp())
                            .background(TitanColors.Accent),
                    )
                }
            }
            // Lifted above the finger, so neither hides the chip or the drop line.
            Box(
                Modifier
                    .zIndex(2f)
                    .offset {
                        val lifted = Offset(current.grab.x, current.chipHeight + liftPx)
                        (current.pointer - lifted - rootOrigin).round()
                    },
            ) {
                ChipFace(current.key, floating = true, removing = removing)
            }
        }
    }
}

/**
 * Where a key dropped at [pointer] goes among the bar's [others] chips, laid
 * out in wrapped rows: after every chip on a row above the finger, and after
 * the ones on its own row whose middle it has passed.
 */
internal fun insertionIndex(others: List<Rect>, pointer: Offset): Int =
    others.count { r -> pointer.y >= r.bottom || (pointer.y >= r.top && pointer.x > r.center.x) }

/** The vertical line that marks insertion [index], just before its chip or after the last. */
private fun insertionLine(others: List<Rect>, index: Int, zone: Rect): Rect {
    val gap = 3f
    return when {
        others.isEmpty() -> Rect(zone.left + 16f, zone.top + 40f, zone.left + 18f, zone.top + 80f)
        index < others.size -> others[index].let { Rect(it.left - gap, it.top, it.left - gap + 2f, it.bottom) }
        else -> others.last().let { Rect(it.right + gap, it.top, it.right + gap + 2f, it.bottom) }
    }
}

private fun Offset.round(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())

@Composable
private fun EditorChip(
    key: KeyBarKey,
    dimmed: Boolean = false,
    ghost: Boolean = false,
    onRect: (Rect) -> Unit = {},
    onTap: (() -> Unit)?,
    onDragStart: ((chip: Rect, finger: Offset) -> Unit)?,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    // pointerInput outlives recompositions (its key is the key's id), so the
    // gesture reads the latest callbacks through these, not the first ones.
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentCancel by rememberUpdatedState(onDragCancel)
    Box(
        Modifier
            .onGloballyPositioned {
                rect = it.boundsInRoot()
                onRect(rect)
            }
            .alpha(if (ghost) 0.3f else 1f)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .then(
                if (onDragStart == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(key.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { finger -> currentStart?.invoke(rect, finger) },
                            onDragEnd = { currentEnd() },
                            onDragCancel = { currentCancel() },
                            onDrag = { change, amount ->
                                change.consume()
                                currentDrag(amount)
                            },
                        )
                    }
                },
            ),
    ) {
        ChipFace(key, dimmed = dimmed)
    }
}

/** How a key looks in the editor: the bar's button, outlined. */
@Composable
private fun ChipFace(key: KeyBarKey, dimmed: Boolean = false, floating: Boolean = false, removing: Boolean = false) {
    val shape = RoundedCornerShape(TitanDimens.RadiusSm)
    Box(
        Modifier
            .background(if (floating) TitanColors.SurfaceElevated else TitanColors.Surface, shape)
            .border(
                TitanDimens.Hairline,
                when {
                    removing -> TitanColors.Danger
                    floating -> TitanColors.Accent
                    else -> TitanColors.HairlineStrong
                },
                shape,
            )
            .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (dimmed) TitanColors.Stone else TitanColors.Body,
        )
    }
}
