package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
        Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
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
 * A tappable list row: ASCII [marker] + title/subtitle + optional trailing slot.
 */
@Composable
fun ListRow(
    marker: String,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    markerColor: Color = TitanColors.Body,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TitanDimens.TouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = TitanDimens.SpaceSm, horizontal = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, color = markerColor)
        Spacer(Modifier.width(TitanDimens.SpaceMd))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TitanColors.Ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TitanColors.Mute)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(TitanDimens.SpaceSm))
            trailing()
        }
    }
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

/** Standard content padding for a scrollable editor/list body. */
fun bodyPadding(): PaddingValues = PaddingValues(
    horizontal = TitanDimens.SpaceLg,
    vertical = TitanDimens.SpaceMd,
)

/**
 * Editor chrome: a top bar with a `[<]` back action, a title, an optional
 * `[x] Eliminar` and a `[✓] Guardar`, over a scrollable body. [onSave] is only
 * enabled when [canSave] holds.
 */
@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
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
                TitanButton("[x] Eliminar", onClick = onDelete, kind = ButtonKind.DANGER)
                Spacer(Modifier.width(TitanDimens.SpaceSm))
            }
            TitanButton("[ok] Guardar", onClick = onSave, kind = ButtonKind.PRIMARY, enabled = canSave)
        }
        Hairline()
        body()
    }
}
