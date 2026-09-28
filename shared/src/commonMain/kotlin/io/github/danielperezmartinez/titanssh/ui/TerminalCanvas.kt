package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import io.github.danielperezmartinez.titanssh.terminal.AnsiPalette
import io.github.danielperezmartinez.titanssh.terminal.CellStyle
import io.github.danielperezmartinez.titanssh.terminal.TermColor
import io.github.danielperezmartinez.titanssh.terminal.TerminalCell
import io.github.danielperezmartinez.titanssh.terminal.TerminalRow
import io.github.danielperezmartinez.titanssh.terminal.TerminalSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil
import kotlin.math.floor

internal fun packedToColor(packed: Int): Color = Color(0xFF000000L.toInt() or (packed and 0xFFFFFF))

internal val TerminalFg = packedToColor(AnsiPalette.DEFAULT_FG)
internal val TerminalBgColor = packedToColor(AnsiPalette.DEFAULT_BG)
private val CursorColor = packedToColor(AnsiPalette.CURSOR)

/** Pixel size of one terminal cell. */
internal data class CellMetrics(val width: Float, val height: Float)

/**
 * Measures the monospace cell of [style]. The bundled font may load
 * asynchronously, so it is preloaded first and measured again once it is in;
 * the measurer keeps no cache of its own, which would otherwise hand back the
 * fallback font's layout after the real one arrived.
 */
@Composable
internal fun rememberCellMetrics(style: TextStyle): CellMetrics {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val resolver = LocalFontFamilyResolver.current
    var loads by remember(style.fontFamily) { mutableIntStateOf(0) }
    LaunchedEffect(style.fontFamily, resolver) {
        style.fontFamily?.let { runCatching { resolver.preload(it) } }
        loads++
    }
    return remember(style, loads, measurer) {
        val probe = "MMMMMMMMMMMMMMMMMMMM"
        val layout = measurer.measure(probe, style, softWrap = false, maxLines = 1)
        CellMetrics(
            width = layout.getHorizontalPosition(probe.length, usePrimaryDirection = true) / probe.length,
            height = layout.size.height.toFloat(),
        )
    }
}

/** Columns and rows of [cell] that fit in [pane], less [paddingX] on each side. */
internal fun gridFor(pane: IntSize, cell: CellMetrics, paddingX: Float): Pair<Int, Int> {
    val cols = floor((pane.width - 2 * paddingX) / cell.width).toInt().coerceAtLeast(1)
    val rows = floor(pane.height / cell.height).toInt().coerceAtLeast(1)
    return cols to rows
}

/**
 * Where the terminal pane is and how far the user scrolled back into the
 * scrollback. Shared by the gesture handler, which moves it, and the painter,
 * which knows how far it can go.
 */
@Stable
internal class TerminalViewport {
    var paneSize by mutableStateOf(IntSize.Zero)

    /** How far the content is pushed down to show older lines, in px (0: live screen). */
    var scrollBackPx by mutableFloatStateOf(0f)

    /** The largest [scrollBackPx] the content allows; the painter updates it each frame. */
    var maxScrollBackPx = 0f

    /** Applies a vertical drag or wheel [delta] and returns the part consumed. */
    fun scrollBy(delta: Float): Float {
        val old = scrollBackPx
        scrollBackPx = (old + delta).coerceIn(0f, maxOf(0f, maxScrollBackPx))
        return scrollBackPx - old
    }

    fun toBottom() {
        scrollBackPx = 0f
    }
}

/**
 * Paints a [TerminalSnapshot] on a canvas, every glyph at its cell's exact
 * position so a line always takes exactly its columns, whatever the font does
 * with the characters it lacks.
 *
 * The snapshot is read only in the draw phase: new output repaints the canvas
 * without recomposing anything, and each row keeps what it drew until it
 * changes (rows are immutable and shared between snapshots), so typing a
 * character lays out one row, not the screen.
 *
 * While the pane is being resized (the soft keyboard sliding in) and the new
 * size has not reached the emulator yet, the rows are placed where the resize
 * will put them, so the content follows the keyboard smoothly and does not jump
 * when the resize lands.
 */
@Composable
internal fun TerminalCanvas(
    snapshots: StateFlow<TerminalSnapshot>,
    viewport: TerminalViewport,
    style: TextStyle,
    cell: CellMetrics,
    paddingX: Float,
    showCursor: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshotState = snapshots.collectAsState()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val painter = remember(measurer, style, cell) { TerminalPainter(measurer, style, cell) }

    // Scrolled back while output keeps coming: keep the same lines in view.
    LaunchedEffect(snapshots, viewport, cell) {
        var lastScrollback = -1
        snapshots.collect { s ->
            val grown = s.scrollback.size - lastScrollback
            if (lastScrollback >= 0 && grown > 0 && viewport.scrollBackPx > 0f) {
                viewport.scrollBackPx += grown * cell.height
            }
            lastScrollback = s.scrollback.size
        }
    }

    Spacer(
        modifier.drawBehind {
            clipRect {
                drawTerminal(snapshotState.value, painter, viewport, cell, paddingX, showCursor)
            }
        },
    )
}

private fun DrawScope.drawTerminal(
    snap: TerminalSnapshot,
    painter: TerminalPainter,
    viewport: TerminalViewport,
    cell: CellMetrics,
    paddingX: Float,
    showCursor: Boolean,
) {
    val rowsOnScreen = snap.screen.size
    if (rowsOnScreen == 0 || cell.height <= 0f) return
    val ch = cell.height
    val cw = cell.width
    val scrollback = snap.scrollback.size
    val total = scrollback + rowsOnScreen
    val fit = floor(size.height / ch).toInt().coerceAtLeast(1)

    // The document line shown on the top row once the emulator has this height:
    // what the emulator's resize will do (it pulls lines back from the
    // scrollback when taller and pushes them into it when shorter, keeping the
    // cursor line). The alternate screen is only clipped: its app redraws.
    val first = if (snap.altScreen) {
        scrollback
    } else {
        val pull = maxOf(0, fit - rowsOnScreen)
        minOf(scrollback + snap.cursorRow, maxOf(maxOf(0, scrollback - pull), scrollback + snap.contentRows - fit))
    }
    // Content reaching the bottom row is glued to the bottom edge (it slides
    // with the keyboard); a short screen stays at the top.
    val bottomAligned = !snap.altScreen && scrollback + snap.contentRows - first >= fit
    val y0 = if (bottomAligned) size.height - fit * ch else 0f

    viewport.maxScrollBackPx = maxOf(0f, first * ch - y0)
    val scroll = viewport.scrollBackPx.coerceIn(0f, viewport.maxScrollBackPx)

    val from = floor(first - (y0 + scroll) / ch).toInt().coerceAtLeast(0)
    val to = ceil(first + (size.height - y0 - scroll) / ch).toInt().coerceAtMost(total - 1)

    painter.beginFrame()
    for (j in from..to) {
        val row = if (j < scrollback) snap.scrollback[j] else snap.screen[j - scrollback]
        val y = y0 + (j - first) * ch + scroll
        painter.draw(this, row, paddingX, y)
    }
    painter.endFrame()

    if (showCursor && snap.cursorVisible) {
        val j = scrollback + snap.cursorRow
        val row = snap.screen.getOrNull(snap.cursorRow) ?: return
        val col = snap.cursorColumn.coerceIn(0, maxOf(0, row.size - 1))
        val under = row.getOrNull(col) ?: TerminalCell.Blank
        val span = if (under.width == 2) 2 else 1
        val x = paddingX + col * cw
        val y = y0 + (j - first) * ch + scroll
        drawRect(CursorColor, Offset(x, y), Size(cw * span, ch))
        if (under.codePoint != ' '.code && under.width != 0) {
            painter.drawGlyph(this, under, TerminalBgColor, x, y, span)
        }
    }
}

/** What one row draws: background runs, one text layout for the grid-safe text, and the glyphs placed one by one. */
private class RowPaint(
    val backgrounds: List<Pair<IntRange, Color>>,
    val text: TextLayoutResult?,
    val glyphs: List<PlacedGlyph>,
)

private class PlacedGlyph(val col: Int, val span: Int, val layout: TextLayoutResult)

private data class GlyphKey(val text: String, val color: Color, val bold: Boolean, val italic: Boolean)

/**
 * Lays out and draws rows, caching the layout of each [TerminalRow] instance
 * for as long as it stays visible.
 */
private class TerminalPainter(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val cell: CellMetrics,
) {
    private var cache = HashMap<TerminalRow, RowPaint>()
    private var next = HashMap<TerminalRow, RowPaint>()
    private val glyphs = HashMap<GlyphKey, TextLayoutResult>()

    fun beginFrame() {
        next.clear()
    }

    fun endFrame() {
        val done = cache
        cache = next
        next = done
    }

    fun draw(scope: DrawScope, row: TerminalRow, x0: Float, y: Float) {
        val paint = next[row] ?: cache[row] ?: layout(row)
        next[row] = paint
        for ((cols, color) in paint.backgrounds) {
            scope.drawRect(
                color,
                Offset(x0 + cols.first * cell.width, y),
                Size((cols.last - cols.first + 1) * cell.width, cell.height),
            )
        }
        paint.text?.let { scope.drawText(it, topLeft = Offset(x0, y)) }
        for (g in paint.glyphs) place(scope, g.layout, x0 + g.col * cell.width, y, g.span)
    }

    /** Draws a single cell's glyph in [color], e.g. the character under the cursor. */
    fun drawGlyph(scope: DrawScope, cellValue: TerminalCell, color: Color, x: Float, y: Float, span: Int) {
        place(scope, glyph(cellValue.text, color, cellValue.style.bold, cellValue.style.italic), x, y, span)
    }

    private fun place(scope: DrawScope, layout: TextLayoutResult, x: Float, y: Float, span: Int) {
        val room = span * cell.width
        val w = layout.size.width.toFloat()
        scope.drawText(layout, topLeft = Offset(if (w < room) x + (room - w) / 2 else x, y))
    }

    private fun layout(row: TerminalRow): RowPaint {
        val backgrounds = ArrayList<Pair<IntRange, Color>>()
        val placed = ArrayList<PlacedGlyph>()
        val text = StringBuilder(row.size)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var runStart = 0
        var runStyle: SpanStyle? = null

        for (col in row.indices) {
            val c = row[col]
            if (c.width == 0) continue
            val span = if (c.width == 2) 2 else 1
            val (fg, bg) = colorsOf(c.style)
            if (bg != null) {
                val last = backgrounds.lastOrNull()
                if (last != null && last.second == bg && last.first.last == col - 1) {
                    backgrounds[backgrounds.size - 1] = (last.first.first..col + span - 1) to bg
                } else {
                    backgrounds.add((col..col + span - 1) to bg)
                }
            }
            val visible = !c.style.invisible && c.codePoint != ' '.code
            val chunk = when {
                !visible -> if (span == 2) "  " else " "
                span == 1 && isGridSafe(c.codePoint) -> c.text
                else -> {
                    // Outside the font's grid-safe set (or wide): placed on its own cells.
                    placed.add(PlacedGlyph(col, span, glyph(c.text, fg, c.style.bold, c.style.italic)))
                    if (span == 2) "  " else " "
                }
            }
            // A blank only matters for its decoration: it joins whatever run it is in.
            val needsStyle = visible && chunk.isNotBlank() || c.style.underline || c.style.strikethrough
            if (needsStyle) {
                val s = spanStyleOf(fg, c.style)
                if (s != runStyle) {
                    runStyle?.let { if (text.length > runStart) spans.add(AnnotatedString.Range(it, runStart, text.length)) }
                    runStart = text.length
                    runStyle = s
                }
            }
            text.append(chunk)
        }
        var end = text.length
        while (end > 0 && text[end - 1] == ' ') end--
        runStyle?.let { if (minOf(end, text.length) > runStart) spans.add(AnnotatedString.Range(it, runStart, end)) }
        val layout = if (end == 0) {
            null
        } else {
            val clipped = spans.mapNotNull { r ->
                if (r.start >= end) null else AnnotatedString.Range(r.item, r.start, minOf(r.end, end))
            }
            measurer.measure(AnnotatedString(text.substring(0, end), clipped), style, softWrap = false, maxLines = 1)
        }
        return RowPaint(backgrounds, layout, placed)
    }

    private fun glyph(text: String, color: Color, bold: Boolean, italic: Boolean): TextLayoutResult {
        val key = GlyphKey(text, color, bold, italic)
        glyphs[key]?.let { return it }
        if (glyphs.size > MAX_GLYPHS) glyphs.clear()
        val s = style.copy(
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        )
        return measurer.measure(text, s, softWrap = false, maxLines = 1).also { glyphs[key] = it }
    }

    private companion object {
        const val MAX_GLYPHS = 512
    }
}

private fun spanStyleOf(fg: Color, s: CellStyle): SpanStyle = SpanStyle(
    color = fg,
    fontWeight = if (s.bold) FontWeight.Bold else null,
    fontStyle = if (s.italic) FontStyle.Italic else null,
    textDecoration = when {
        s.underline && s.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        s.underline -> TextDecoration.Underline
        s.strikethrough -> TextDecoration.LineThrough
        else -> null
    },
)

/** A cell's foreground and (optional, non-default) background, honoring inverse and dim. */
private fun colorsOf(s: CellStyle): Pair<Color, Color?> {
    var fgPacked = AnsiPalette.resolve(s.fg, AnsiPalette.DEFAULT_FG)
    var bgPacked = AnsiPalette.resolve(s.bg, AnsiPalette.DEFAULT_BG)
    var bgIsDefault = s.bg == TermColor.Default
    if (s.inverse) {
        val tmp = fgPacked
        fgPacked = bgPacked
        bgPacked = tmp
        bgIsDefault = false
    }
    var fg = packedToColor(fgPacked)
    if (s.dim) fg = fg.copy(alpha = 0.6f)
    return fg to if (bgIsDefault) null else packedToColor(bgPacked)
}

/**
 * Characters JetBrains Mono draws at exactly one cell's advance: Latin, Greek,
 * Cyrillic, box drawing and blocks. The rest may come from a fallback font of
 * another width, so they are placed cell by cell instead of flowing in the text.
 */
private fun isGridSafe(cp: Int): Boolean =
    cp in 0x20..0x7E || cp in 0xA0..0x24F || cp in 0x370..0x4FF || cp in 0x2500..0x259F
