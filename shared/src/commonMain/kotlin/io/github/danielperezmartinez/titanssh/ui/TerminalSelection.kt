package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import io.github.danielperezmartinez.titanssh.terminal.DocPos
import io.github.danielperezmartinez.titanssh.terminal.TerminalSnapshot
import io.github.danielperezmartinez.titanssh.terminal.TerminalText
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How often the selection scrolls while the finger rests at the pane's top or bottom edge. */
private const val EDGE_SCROLL_MILLIS = 50L

private val SelectionFill = TitanColors.Accent.copy(alpha = 0.35f)
private val HandleColor = TitanColors.Accent

/** The two ends of a selection, as the handles that drag them. */
internal enum class SelectionHandle { START, END }

/**
 * The text selected on a terminal pane, in document positions ([DocPos]) so it
 * stays on the same text while output scrolls. The painter reads it only in the
 * draw phase: moving it repaints the pane and recomposes nothing.
 *
 * It belongs to the [TerminalSnapshot.layoutEpoch] and screen it was made on;
 * once the lines are rewritten (a resize re-flow, a cleared scrollback) or the
 * remote switches screens, [isStale] says it no longer points at its text.
 */
@Stable
internal class TerminalSelection(
    /** Radius of a handle's round part, in px. */
    val handleRadius: Float,
) {
    /** Where the selection began; it stays put while [extent] follows the finger. */
    var anchor: DocPos? by mutableStateOf(null)
        private set
    var extent: DocPos? by mutableStateOf(null)
        private set

    private var epoch = 0
    private var altScreen = false

    val isActive: Boolean get() = anchor != null && extent != null

    fun select(anchor: DocPos, extent: DocPos, snap: TerminalSnapshot) {
        this.anchor = anchor
        this.extent = extent
        epoch = snap.layoutEpoch
        altScreen = snap.altScreen
    }

    /** Moves both ends within the same epoch (a drag growing a word selection). */
    fun update(anchor: DocPos, extent: DocPos) {
        this.anchor = anchor
        this.extent = extent
    }

    fun moveExtent(extent: DocPos) {
        if (anchor != null) this.extent = extent
    }

    /** Makes [handle]'s end the one that moves. */
    fun grab(handle: SelectionHandle) {
        val (start, end) = range() ?: return
        if (handle == SelectionHandle.START) update(end, start) else update(start, end)
    }

    fun clear() {
        anchor = null
        extent = null
    }

    /** The selection's first and last cell, in document order. */
    fun range(): Pair<DocPos, DocPos>? {
        val a = anchor ?: return null
        val e = extent ?: return null
        return if (a <= e) a to e else e to a
    }

    fun isStale(snap: TerminalSnapshot): Boolean {
        val (start, _) = range() ?: return false
        return snap.layoutEpoch != epoch || snap.altScreen != altScreen || start.line < snap.droppedLines
    }

    /** The corner each handle points at: the start cell's bottom-left and the end cell's bottom-right. */
    fun handlePoints(snap: TerminalSnapshot, g: TerminalGeometry): Pair<Offset, Offset>? {
        val (s, e) = range() ?: return null
        val (start, end) = TerminalText.expand(snap, s, e)
        val ch = g.cell.height
        val startTop = g.top((start.line - snap.droppedLines).toInt())
        val endTop = g.top((end.line - snap.droppedLines).toInt())
        return Offset(g.x(start.column), startTop + ch) to Offset(g.x(end.column + 1), endTop + ch)
    }

    /** The handle whose round part is under [point], if any. */
    fun handleAt(point: Offset, snap: TerminalSnapshot, g: TerminalGeometry): SelectionHandle? {
        val (start, end) = handlePoints(snap, g) ?: return null
        val r = handleRadius
        val reach = r * 2.2f
        val toStart = (point - Offset(start.x - r, start.y + r)).getDistance()
        val toEnd = (point - Offset(end.x + r, end.y + r)).getDistance()
        return when {
            toStart > reach && toEnd > reach -> null
            toStart <= toEnd -> SelectionHandle.START
            else -> SelectionHandle.END
        }
    }

    /** What the selection covers on screen, clipped to a pane [height] px tall: where the menu goes. */
    fun bounds(snap: TerminalSnapshot, g: TerminalGeometry, width: Float, height: Float): Rect? {
        val (start, end) = handlePoints(snap, g) ?: return null
        val ch = g.cell.height
        val top = (start.y - ch).coerceIn(0f, height)
        val bottom = end.y.coerceIn(0f, height)
        val left = if (start.y == end.y) start.x else 0f
        val right = if (start.y == end.y) end.x else width
        return Rect(left, top, right, maxOf(top, bottom))
    }
}

/** Paints [selection] over rows [from]..[to] (indexes into scrollback + screen), then its handles. */
internal fun DrawScope.drawSelection(selection: TerminalSelection, snap: TerminalSnapshot, g: TerminalGeometry, from: Int, to: Int) {
    val (s, e) = selection.range() ?: return
    val (start, end) = TerminalText.expand(snap, s, e)
    val ch = g.cell.height
    val cw = g.cell.width
    for (j in from..to) {
        val line = TerminalText.lineOf(snap, j)
        if (line < start.line || line > end.line) continue
        val width = maxOf(snap.columns, TerminalText.rowAt(snap, line)?.size ?: 0)
        val c0 = if (line == start.line) start.column else 0
        val c1 = if (line == end.line) end.column else width - 1
        if (c1 < c0) continue
        drawRect(SelectionFill, Offset(g.x(c0), g.top(j)), Size((c1 - c0 + 1) * cw, ch))
    }
    val (a, b) = selection.handlePoints(snap, g) ?: return
    val r = selection.handleRadius
    // Android's teardrop: a disc with the corner that touches the text squared off.
    drawCircle(HandleColor, r, Offset(a.x - r, a.y + r))
    drawRect(HandleColor, Offset(a.x - r, a.y), Size(r, r))
    drawCircle(HandleColor, r, Offset(b.x + r, b.y + r))
    drawRect(HandleColor, Offset(b.x, b.y), Size(r, r))
}

/** What the terminal does with the touches [terminalTouch] recognizes. */
internal class TerminalTouchActions {
    /** A short tap. */
    var onTap: () -> Unit = {}

    /** A long press landed (on a word or not): time for haptic feedback. */
    var onLongPress: () -> Unit = {}

    /** The selection starts moving under the finger. */
    var onSelecting: () -> Unit = {}

    /** The finger let go of the selection. */
    var onSelected: () -> Unit = {}

    /** A long press on a blank: there is nothing to select, but there is something to paste. */
    var onBlankLongPress: (Offset) -> Unit = {}
}

private enum class Press { TAP, LONG_PRESS, NONE }

/**
 * Touch selection on a terminal pane: a long press selects the word under the
 * finger and dragging on grows the selection, scrolling the scrollback at the
 * edges; the handles move either end. Taps go to [actions]. A drag that is not
 * a selection is left alone, so the pane's scrollable still scrolls it.
 */
internal fun Modifier.terminalTouch(
    snapshots: StateFlow<TerminalSnapshot>,
    viewport: TerminalViewport,
    selection: TerminalSelection,
    cell: CellMetrics,
    paddingX: Float,
    actions: TerminalTouchActions,
): Modifier = pointerInput(snapshots, viewport, selection, cell, paddingX, actions) {
    fun geometry(): Pair<TerminalSnapshot, TerminalGeometry>? {
        val snap = snapshots.value
        val g = terminalGeometry(snap, viewport.paneSize.height.toFloat(), cell, paddingX, viewport.scrollBackPx) ?: return null
        return snap to g
    }

    fun positionAt(snap: TerminalSnapshot, g: TerminalGeometry, point: Offset): DocPos {
        val index = g.indexAt(point.y).coerceIn(0, g.total - 1)
        val row = TerminalText.rowAt(snap, TerminalText.lineOf(snap, index))
        val column = g.columnAt(point.x).coerceIn(0, maxOf(0, (row?.size ?: snap.columns) - 1))
        return DocPos(TerminalText.lineOf(snap, index), column)
    }

    /**
     * Moves the selection with the finger until it lifts, through [update],
     * aiming at the finger plus [aim] and scrolling at the edges when
     * [scrollAtEdges]. Events are taken in the initial pass and consumed, so the
     * scrollable below never sees this drag.
     */
    suspend fun AwaitPointerEventScope.follow(
        scope: CoroutineScope,
        down: PointerInputChange,
        aim: Offset,
        scrollAtEdges: Boolean = true,
        update: (DocPos) -> Unit,
    ) {
        var finger = down.position
        fun apply() {
            val (snap, g) = geometry() ?: return
            update(positionAt(snap, g, finger + aim))
        }
        val edgeScroll = if (!scrollAtEdges) null else scope.launch {
            while (true) {
                delay(EDGE_SCROLL_MILLIS)
                val height = viewport.paneSize.height
                val delta = when {
                    finger.y < cell.height -> cell.height
                    finger.y > height - cell.height -> -cell.height
                    else -> 0f
                }
                if (delta != 0f && viewport.scrollBy(delta) != 0f) apply()
            }
        }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    change.consume()
                    break
                }
                if (change.positionChanged()) {
                    finger = change.position
                    apply()
                }
                change.consume()
            }
        } finally {
            edgeScroll?.cancel()
        }
    }

    suspend fun AwaitPointerEventScope.tapOrLongPress(down: PointerInputChange): Press {
        var press = Press.NONE
        val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.size > 1) return@withTimeoutOrNull
                if (change.changedToUpIgnoreConsumed()) {
                    if (!change.isConsumed) press = Press.TAP
                    return@withTimeoutOrNull
                }
                // Moved (the scrollable took it) or claimed by someone else: not a press.
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull
                }
            }
        } == null
        return if (timedOut) Press.LONG_PRESS else press
    }

    coroutineScope {
        val scope = this
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val ch = cell.height
            val cw = cell.width

            val handle = geometry()?.let { (snap, g) ->
                if (selection.isActive) selection.handleAt(down.position, snap, g) else null
            }
            if (handle != null) {
                val (snap, g) = geometry() ?: return@awaitEachGesture
                val (startCorner, endCorner) = selection.handlePoints(snap, g) ?: return@awaitEachGesture
                down.consume()
                selection.grab(handle)
                actions.onSelecting()
                // Aim at the middle of the cell the handle points at, not at the finger on the handle.
                val aim = if (handle == SelectionHandle.START) {
                    startCorner + Offset(cw / 2, -ch / 2) - down.position
                } else {
                    endCorner + Offset(-cw / 2, -ch / 2) - down.position
                }
                follow(scope, down, aim) { selection.moveExtent(it) }
                actions.onSelected()
                return@awaitEachGesture
            }

            when (tapOrLongPress(down)) {
                Press.TAP -> actions.onTap()
                Press.NONE -> Unit
                Press.LONG_PRESS -> {
                    val (snap, g) = geometry() ?: return@awaitEachGesture
                    val word = TerminalText.wordAt(snap, positionAt(snap, g, down.position))
                    actions.onLongPress()
                    if (word == null) {
                        selection.clear()
                        actions.onBlankLongPress(down.position)
                        // Swallow the rest of the press so it does not scroll.
                        follow(scope, down, Offset.Zero, scrollAtEdges = false) {}
                        return@awaitEachGesture
                    }
                    val (wordStart, wordEnd) = word
                    selection.select(wordStart, wordEnd, snap)
                    actions.onSelecting()
                    follow(scope, down, Offset.Zero) { p ->
                        if (p < wordStart) selection.update(wordEnd, p) else selection.update(wordStart, maxOf(p, wordEnd))
                    }
                    actions.onSelected()
                }
            }
        }
    }
}
