package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * Reusable UI primitives for titan-ssh, built to the dark-first visual language:
 * everything monospace, ASCII bracket markers as icons, flat surfaces separated
 * by 1px hairlines, 4px interactive / 0px container radii. Deliberately avoids
 * Material chrome (elevation, rounded cards, animated labels) that would fight
 * the terminal identity.
 */

/** A 1px hairline divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(TitanDimens.Hairline)
            .background(TitanColors.HairlineStrong.copy(alpha = 0.5f)),
    )
}

/** Section label (heading token) with generous top spacing. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = TitanColors.Ink,
        modifier = modifier.padding(top = TitanDimens.SpaceLg, bottom = TitanDimens.SpaceSm),
    )
}

/** Small muted caption, e.g. a field label or metadata line. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = TitanColors.Mute) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/**
 * Flat text field: a labeled BasicTextField inside a hairline box on the
 * `surface` color, 4px radius. No Material label animation.
 */
@Composable
fun TitanTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Caption(label)
            Spacer(Modifier.height(TitanDimens.SpaceXs))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .border(
                    TitanDimens.Hairline,
                    TitanColors.HairlineStrong,
                    RoundedCornerShape(TitanDimens.RadiusSm),
                )
                .background(
                    if (enabled) TitanColors.Surface else TitanColors.Canvas,
                    RoundedCornerShape(TitanDimens.RadiusSm),
                )
                .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TitanColors.Stone,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.copy(color = TitanColors.Ink),
                cursorBrush = SolidColor(TitanColors.Accent),
                visualTransformation =
                    if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Labeled dropdown selector. Renders the current selection in a hairline box;
 * tapping opens a flat menu of [options]. Generic so it drives enums, hosts, etc.
 */
@Composable
fun <T> TitanDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    placeholder: String = "—",
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Caption(label)
            Spacer(Modifier.height(TitanDimens.SpaceXs))
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(TitanDimens.Hairline, TitanColors.HairlineStrong, RoundedCornerShape(TitanDimens.RadiusSm))
                    .background(TitanColors.Surface, RoundedCornerShape(TitanDimens.RadiusSm))
                    .clickable { expanded = true }
                    .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceMd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.let(optionLabel) ?: placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected != null) TitanColors.Ink else TitanColors.Stone,
                    modifier = Modifier.weight(1f),
                )
                Text("[v]", style = MaterialTheme.typography.labelSmall, color = TitanColors.Mute)
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium, color = TitanColors.Body) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Visual weight of a [TitanButton]. */
enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

/**
 * Flat button: bracketed mono label, 4px radius, color by [kind]. Primary fills
 * with `accent`; secondary is a hairline outline; danger uses the danger color.
 */
@Composable
fun TitanButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    // Color discipline: the primary action reads as primary through an elevated
    // fill + a thin accent hairline (accent = interactive emphasis, its product
    // role), never by flooding it with a semantic color. Danger keeps the danger
    // color because destructive actions are a genuine state.
    val (bg, fg, border) = when (kind) {
        ButtonKind.PRIMARY -> Triple(TitanColors.SurfaceElevated, TitanColors.Ink, TitanColors.Accent)
        ButtonKind.SECONDARY -> Triple(Color.Transparent, TitanColors.Body, TitanColors.HairlineStrong)
        ButtonKind.DANGER -> Triple(Color.Transparent, TitanColors.Danger, TitanColors.Danger)
    }
    Box(
        modifier
            .defaultMinSize(minHeight = TitanDimens.TouchTarget)
            .border(TitanDimens.Hairline, if (enabled) border else TitanColors.Stone, RoundedCornerShape(TitanDimens.RadiusSm))
            .background(if (enabled) bg else Color.Transparent, RoundedCornerShape(TitanDimens.RadiusSm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TitanDimens.SpaceLg, vertical = TitanDimens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) fg else TitanColors.Stone,
        )
    }
}

/**
 * ASCII toggle rendered as `[x] label` / `[ ] label`, a full-width touch row.
 */
@Composable
fun TitanCheck(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TitanDimens.TouchTarget)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A checked toggle uses the interactive accent (a selection), not the
        // green success state, which is reserved for a live connection.
        Text(
            text = if (checked) "[x]" else "[ ]",
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) TitanColors.Accent else TitanColors.Mute,
        )
        Spacer(Modifier.width(TitanDimens.SpaceSm))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TitanColors.Body)
    }
}

/**
 * Single-choice selector rendered as a wrap of bracketed options; the selected
 * one is filled. Fits the mono aesthetic and works on both touch and pointer.
 */
@Composable
fun <T> TitanSegmented(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
) {
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Caption(label)
            Spacer(Modifier.height(TitanDimens.SpaceXs))
        }
        // Wraps onto new lines instead of squeezing the options on a narrow screen.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
        ) {
            options.forEach { option ->
                val isSel = option == selected
                Box(
                    Modifier
                        .border(
                            TitanDimens.Hairline,
                            if (isSel) TitanColors.Accent else TitanColors.HairlineStrong,
                            RoundedCornerShape(TitanDimens.RadiusSm),
                        )
                        .background(
                            if (isSel) TitanColors.Accent.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(TitanDimens.RadiusSm),
                        )
                        .clickable { onSelect(option) }
                        .padding(horizontal = TitanDimens.SpaceMd, vertical = TitanDimens.SpaceSm),
                ) {
                    Text(
                        optionLabel(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) TitanColors.Ink else TitanColors.Mute,
                    )
                }
            }
        }
    }
}

/**
 * A list row: ASCII [marker] + [title]/[subtitle] + optional [trailing] slot,
 * optionally followed by [expandedContent]. Every part is opt-in and the row
 * decides nothing on its own: a zone only reacts if the caller gives it an
 * action, so the same row serves a plain entry, a launcher or a menu.
 *
 * - [onClick]: tapping the row. `null` leaves the row inert.
 * - [onLongClick]: holding the row; on desktop a right click does the same.
 * - [onMarkerClick]: tapping the marker on its own (a zone the full height of
 *   the row). `null` makes the marker part of the row, so it gets [onClick].
 * - [note]: an optional third line under [subtitle], in [noteColor] (e.g. a
 *   state such as "viva en el destino", in warning when it needs attention).
 * - [trailing]: any content at the end, e.g. a button with its own action.
 * - [expanded] / [expandedContent]: content shown under the row, typically
 *   more [ListRow]s acting as contextual actions. The caller owns [expanded]
 *   (and so decides e.g. that only one row is open at a time) and wires which
 *   gesture toggles it.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    note: String? = null,
    noteColor: Color = TitanColors.Mute,
    marker: String? = null,
    markerColor: Color = TitanColors.Body,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMarkerClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    expanded: Boolean = false,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val secondaryClick by rememberUpdatedState(onLongClick)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TitanDimens.TouchTarget)
                .height(IntrinsicSize.Min)
                .then(
                    if (onLongClick == null) {
                        Modifier
                    } else {
                        // A right click is desktop's long press. Taken on the
                        // initial pass so the row's click never sees it.
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                        event.changes.forEach { it.consume() }
                                        secondaryClick?.invoke()
                                    }
                                }
                            }
                        }
                    },
                )
                .then(
                    if (onClick == null && onLongClick == null) {
                        Modifier
                    } else {
                        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (marker != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .then(if (onMarkerClick != null) Modifier.clickable(onClick = onMarkerClick) else Modifier)
                        .padding(start = TitanDimens.SpaceXs, end = TitanDimens.SpaceMd),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(marker, style = MaterialTheme.typography.bodyLarge, color = markerColor)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = TitanDimens.SpaceSm)
                    .padding(start = if (marker == null) TitanDimens.SpaceXs else 0.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = TitanColors.Ink)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TitanColors.Mute)
                }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = noteColor)
                }
            }
            if (trailing != null) {
                Row(
                    Modifier.padding(start = TitanDimens.SpaceSm, end = TitanDimens.SpaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (expandedContent != null) {
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                // Flat surface, indented under the row it belongs to.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(TitanColors.Surface)
                        .padding(start = TitanDimens.SpaceXl),
                    content = expandedContent,
                )
            }
        }
    }
}

/**
 * An inline confirmation built on [ListRow]: the [question] with a confirm and
 * a cancel button, for a step that must not happen on a single tap (e.g.
 * deleting). Shown in place of the row that asked for it; no dialog.
 */
@Composable
fun ConfirmRow(
    question: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    marker: String? = null,
    markerColor: Color = TitanColors.Body,
    cancelLabel: String = "[<] No",
    confirmKind: ButtonKind = ButtonKind.DANGER,
) {
    ListRow(
        title = question,
        modifier = modifier,
        subtitle = subtitle,
        marker = marker,
        markerColor = markerColor,
        trailing = {
            TitanButton(cancelLabel, onClick = onCancel, kind = ButtonKind.SECONDARY)
            Spacer(Modifier.width(TitanDimens.SpaceSm))
            TitanButton(confirmLabel, onClick = onConfirm, kind = confirmKind)
        },
    )
}

/** Centered empty-state message for a list. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(TitanDimens.SpaceXl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TitanColors.Stone)
    }
}

/** Small ghost icon-button showing a bracketed glyph, e.g. `[^]`, `[v]`, `[x]`. */
@Composable
fun GlyphButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TitanColors.Mute,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = TitanDimens.TouchTarget, minHeight = TitanDimens.TouchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else TitanColors.Stone)
    }
}

/** A `[<]` back action and a title, the read-only counterpart of [EditorScaffold]'s bar. */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = TitanColors.Ink,
            modifier = Modifier.weight(1f).padding(start = TitanDimens.SpaceSm),
        )
    }
}

/** Standard content padding for a scrollable editor/list body. */
fun bodyPadding(): PaddingValues = PaddingValues(
    horizontal = TitanDimens.SpaceLg,
    vertical = TitanDimens.SpaceMd,
)

/**
 * Editor chrome: a top bar with a `[<]` back action, a title, an optional
 * delete action ([deleteLabel], `[x] Eliminar` by default) and a `[✓] Guardar`,
 * over a scrollable body. [onSave] is only enabled when [canSave] holds.
 */
@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "[x] Eliminar",
    body: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = TitanDimens.SpaceSm, vertical = TitanDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TitanColors.Ink,
                modifier = Modifier.weight(1f).padding(start = TitanDimens.SpaceSm),
            )
            if (onDelete != null) {
                TitanButton(deleteLabel, onClick = onDelete, kind = ButtonKind.DANGER)
                Spacer(Modifier.width(TitanDimens.SpaceSm))
            }
            TitanButton("[ok] Guardar", onClick = onSave, kind = ButtonKind.PRIMARY, enabled = canSave)
        }
        Hairline()
        body()
    }
}
