package im.gar.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.gar.titanssh.terminal.AccessoryKey
import im.gar.titanssh.terminal.AnsiPalette
import im.gar.titanssh.terminal.DefaultAccessoryKeys
import im.gar.titanssh.terminal.ModifierKind
import im.gar.titanssh.terminal.SessionTab
import im.gar.titanssh.terminal.TabPhase
import im.gar.titanssh.terminal.TermColor
import im.gar.titanssh.terminal.TerminalCell
import im.gar.titanssh.terminal.TerminalKeys
import im.gar.titanssh.terminal.TerminalLine
import im.gar.titanssh.terminal.isAndroidRuntime
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch

private fun packedToColor(packed: Int): Color =
    Color(0xFF000000L.toInt() or (packed and 0xFFFFFF))

private val TerminalFg = packedToColor(AnsiPalette.DEFAULT_FG)
private val TerminalBgColor = packedToColor(AnsiPalette.DEFAULT_BG)

/**
 * Renders one [SessionTab]: the terminal grid, a status/host-key overlay, and
 * input. Keyboard input is captured on all platforms via key events (physical
 * keyboards and desktop); on Android an accessory key bar supplies the keys the
 * soft keyboard lacks (Esc, Tab, Ctrl, Alt, arrows, `| / - ~`) plus paste, and a
 * hidden capture field brings up the soft keyboard for text.
 */
@Composable
fun TerminalView(tab: SessionTab, modifier: Modifier = Modifier) {
    val snapshot by tab.snapshot.collectAsState()
    val status by tab.status.collectAsState()
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

    fun send(bytes: ByteArray) {
        scope.launch { tab.sendBytes(bytes) }
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

    // Measure a monospace cell to translate the pane size into columns/rows.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellSize = remember(fontSize, monoFamily) {
        textMeasurer.measure(
            AnnotatedString("MMMMMMMMMM"),
            style = androidx.compose.ui.text.TextStyle(fontFamily = monoFamily, fontSize = fontSize),
        ).size
    }
    val cellWidthPx = (cellSize.width / 10f).coerceAtLeast(1f)
    val cellHeightPx = cellSize.height.coerceAtLeast(1).toFloat()

    var columns by remember { mutableStateOf(80) }
    var rows by remember { mutableStateOf(24) }

    LaunchedEffect(columns, rows, tab.id) {
        tab.resize(columns, rows)
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

        StatusStrip(status)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    val c = (size.width / cellWidthPx).toInt().coerceAtLeast(1)
                    val r = (size.height / cellHeightPx).toInt().coerceAtLeast(1)
                    if (c != columns) columns = c
                    if (r != rows) rows = r
                }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event -> handleKeyEvent(event, { send(it) }, { sendTyped(it) }) }
                .clickable {
                    if (isAndroidRuntime()) {
                        keyboardFocus.requestFocus()
                        keyboardController?.show()
                    } else {
                        focusRequester.requestFocus()
                    }
                },
        ) {
            TerminalGrid(
                lines = remember(snapshot) { snapshot.scrollback + snapshot.screen },
                cursorLineIndex = snapshot.scrollback.size + snapshot.cursorRow,
                cursorColumn = snapshot.cursorColumn,
                showCursor = status.phase == TabPhase.CONNECTED,
                monoFamily = monoFamily,
                fontSize = fontSize,
            )
        }

        LaunchedEffect(tab.id) { focusRequester.requestFocus() }

        if (isAndroidRuntime()) {
            Hairline()
            val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
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
                onPaste = { clipboard.getText()?.text?.let { send(TerminalKeys.text(it)) } },
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

@Composable
private fun TerminalGrid(
    lines: List<TerminalLine>,
    cursorLineIndex: Int,
    cursorColumn: Int,
    showCursor: Boolean,
    monoFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = TitanDimens.SpaceXs)) {
        itemsIndexed(lines) { index, line ->
            val cursor = if (showCursor && index == cursorLineIndex) cursorColumn else -1
            Text(
                text = renderLine(line, cursor),
                fontFamily = monoFamily,
                fontSize = fontSize,
                color = TerminalFg,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/** Builds a colored, single-line [AnnotatedString] from a row of cells. */
private fun renderLine(line: TerminalLine, cursorColumn: Int): AnnotatedString = buildAnnotatedString {
    if (line.isEmpty()) {
        append(" ")
        return@buildAnnotatedString
    }
    line.forEachIndexed { col, cell ->
        val isCursor = col == cursorColumn
        val (fg, bg) = cellColors(cell, isCursor)
        withStyle(
            SpanStyle(
                color = fg,
                background = bg ?: Color.Unspecified,
                fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
            ),
        ) {
            append(if (cell.char == ' ') ' ' else cell.char)
        }
    }
}

/** Resolves a cell's foreground and (optional) background, honoring inverse and the cursor. */
private fun cellColors(cell: TerminalCell, isCursor: Boolean): Pair<Color, Color?> {
    var fgPacked = AnsiPalette.resolve(cell.fg, AnsiPalette.DEFAULT_FG)
    var bgIsDefault = cell.bg == TermColor.Default
    var bgPacked = AnsiPalette.resolve(cell.bg, AnsiPalette.DEFAULT_BG)

    if (cell.inverse) {
        val tmp = fgPacked
        fgPacked = bgPacked
        bgPacked = tmp
        bgIsDefault = false
    }
    if (isCursor) {
        // Cursor block: paint the cell with the accent, dark glyph.
        return TerminalBgColor to packedToColor(AnsiPalette.CURSOR)
    }
    val bg = if (bgIsDefault) null else packedToColor(bgPacked)
    return packedToColor(fgPacked) to bg
}

/** A thin strip showing the tab's connection phase with a semantic marker/color. */
@Composable
private fun StatusStrip(status: im.gar.titanssh.terminal.TabStatus) {
    val (marker, color, label) = when (status.phase) {
        TabPhase.CONNECTING -> Triple("[-]", TitanColors.Warning, "Conectando…")
        TabPhase.CONNECTED -> Triple("[+]", TitanColors.Success, "Conectado")
        TabPhase.RECONNECTING -> Triple("[-]", TitanColors.Warning, "Reconectando…")
        TabPhase.DISCONNECTED -> Triple("[x]", TitanColors.Danger, "Caída")
        TabPhase.FAILED -> Triple("[x]", TitanColors.Danger, "Fallo")
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
            status.detail ?: label,
            style = MaterialTheme.typography.labelSmall,
            color = TitanColors.Mute,
        )
    }
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
