package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.Group
import io.github.danielperezmartinez.titanssh.config.GroupScope
import io.github.danielperezmartinez.titanssh.config.GroupTree
import io.github.danielperezmartinez.titanssh.config.GroupedRow
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * The [rows] of a list shown in folders ([GroupTree.rows]), each indented by
 * its depth and followed by a hairline. Without groups it is the flat list.
 */
internal fun <T> LazyListScope.groupedRows(
    rows: List<GroupedRow<T>>,
    itemKey: (T) -> Any,
    folder: @Composable (GroupedRow.Folder) -> Unit,
    item: @Composable (T) -> Unit,
) {
    items(
        rows,
        key = { row ->
            when (row) {
                is GroupedRow.Folder -> "folder-${row.group.id}"
                is GroupedRow.Item -> itemKey(row.item)
            }
        },
    ) { row ->
        Column(Modifier.fillMaxWidth().padding(start = TitanDimens.SpaceLg * row.depth)) {
            when (row) {
                is GroupedRow.Folder -> folder(row)
                is GroupedRow.Item -> item(row.item)
            }
            Hairline()
        }
    }
}

/** "3 sesiones", "1 sesión", "vacío". */
internal fun folderCount(count: Int, singular: String, plural: String): String = when (count) {
    0 -> "vacío"
    1 -> "1 $singular"
    else -> "$count $plural"
}

/**
 * A group's folder row: `[#]`, its name and how much it holds. Tapping it, or
 * its `[+]`/`[-]`, folds or unfolds it. With [onActions] its marker or a long
 * press opens its [actions] below it, as any other list row.
 */
@Composable
internal fun FolderRow(
    folder: GroupedRow.Folder,
    count: String,
    onToggle: () -> Unit,
    onActions: (() -> Unit)? = null,
    actionsOpen: Boolean = false,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ListRow(
        marker = "[#]",
        markerColor = if (actionsOpen) TitanColors.Accent else TitanColors.Body,
        title = folder.group.name,
        subtitle = count,
        onClick = onToggle,
        onLongClick = onActions,
        onMarkerClick = onActions,
        trailing = { GlyphButton(if (folder.group.collapsed) "[+]" else "[-]", onClick = onToggle) },
        expanded = actionsOpen,
        expandedContent = actions,
    )
}

/**
 * An inline row to type a name (a new group, a rename): a text field with
 * `[<]` to cancel and [confirmLabel] to accept. A blank name is not accepted.
 */
@Composable
internal fun NameEntryRow(
    label: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    initial: String = "",
    confirmLabel: String = "[ok] Crear",
) {
    var name by remember { mutableStateOf(initial) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = TitanDimens.SpaceSm, horizontal = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
    ) {
        Box(Modifier.weight(1f)) {
            TitanTextField(label = label, value = name, onValueChange = { name = it }, placeholder = "p. ej. cliente-x")
        }
        TitanButton("[<]", onClick = onCancel, kind = ButtonKind.SECONDARY)
        TitanButton(confirmLabel, onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, kind = ButtonKind.PRIMARY)
    }
}

/** The step a folder's actions are on, when one asks for more than a tap. */
internal enum class GroupStep { NEW_SUBGROUP, RENAME, MOVE, DELETE }

/**
 * The actions of a folder in Configuración: create an entry in it, a
 * subgroup, rename, move it into another group and delete it (its contents
 * move up to its parent). [step] is the action in progress; [onClose] folds the
 * actions away.
 */
@Composable
internal fun GroupActions(
    controller: ConfigController,
    scope: GroupScope,
    tree: GroupTree,
    folder: GroupedRow.Folder,
    step: GroupStep?,
    onStep: (GroupStep?) -> Unit,
    newEntryLabel: String,
    onNewEntry: () -> Unit,
    onClose: () -> Unit,
) {
    val group = folder.group
    when (step) {
        null -> {
            ListRow(marker = "[+]", title = newEntryLabel, onClick = onNewEntry)
            ListRow(marker = "[+]", title = "Nuevo subgrupo", onClick = { onStep(GroupStep.NEW_SUBGROUP) })
            ListRow(marker = "[~]", title = "Renombrar", onClick = { onStep(GroupStep.RENAME) })
            ListRow(marker = "[#]", title = "Mover a otro grupo", onClick = { onStep(GroupStep.MOVE) })
            ListRow(marker = "[x]", title = "Eliminar", markerColor = TitanColors.Danger, onClick = { onStep(GroupStep.DELETE) })
        }
        GroupStep.NEW_SUBGROUP -> NameEntryRow(
            label = "Subgrupo de ${group.name}",
            onConfirm = { name ->
                controller.createGroup(scope, name, parentId = group.id)
                if (group.collapsed) controller.setGroupCollapsed(scope, group.id, false)
                onClose()
            },
            onCancel = { onStep(null) },
        )
        GroupStep.RENAME -> NameEntryRow(
            label = "Nombre del grupo",
            initial = group.name,
            confirmLabel = "[ok] Guardar",
            onConfirm = { name ->
                controller.renameGroup(scope, group.id, name)
                onClose()
            },
            onCancel = { onStep(null) },
        )
        GroupStep.MOVE -> {
            val targets = tree.moveTargets(group.id).filterNot { it.id == group.parentId }
            Caption("Mover ${group.name} a:", modifier = Modifier.padding(TitanDimens.SpaceSm))
            if (group.parentId != null) {
                ListRow(marker = "[#]", title = "Fuera de carpetas", onClick = {
                    controller.moveGroup(scope, group.id, null)
                    onClose()
                })
            }
            targets.forEach { target ->
                ListRow(marker = "[#]", title = tree.path(target.id), onClick = {
                    controller.moveGroup(scope, group.id, target.id)
                    onClose()
                })
            }
            if (group.parentId == null && targets.isEmpty()) {
                Caption("No hay otro grupo al que moverlo.", modifier = Modifier.padding(TitanDimens.SpaceSm))
            }
            ListRow(marker = "[<]", title = "Cancelar", onClick = { onStep(null) })
        }
        GroupStep.DELETE -> {
            val holds = folder.itemCount > 0 || tree.descendants(group.id).isNotEmpty()
            val destination = group.parentId?.takeIf { tree.contains(it) }?.let { tree.path(it) }
            ConfirmRow(
                question = "¿Eliminar el grupo?",
                subtitle = if (holds) "Lo que contiene pasa a ${destination ?: "fuera de carpetas"}" else null,
                confirmLabel = "[x] Sí",
                marker = "[x]",
                markerColor = TitanColors.Danger,
                onConfirm = {
                    controller.deleteGroup(scope, group.id)
                    onClose()
                },
                onCancel = { onStep(null) },
            )
        }
    }
}

/**
 * The group field of the host and session editors: a selector of [scope]'s
 * groups, shown with their path, plus a way to create one without leaving the
 * editor (it is created at the top and selected).
 */
@Composable
internal fun GroupPicker(
    controller: ConfigController,
    scope: GroupScope,
    groups: List<Group>,
    groupId: String?,
    onGroupId: (String?) -> Unit,
) {
    val tree = remember(groups) { GroupTree(groups) }
    var creating by remember { mutableStateOf(false) }
    TitanDropdown(
        "Grupo",
        options = listOf<Group?>(null) + tree.ordered(),
        selected = groups.firstOrNull { it.id == groupId },
        onSelect = { onGroupId(it?.id) },
        optionLabel = { group -> group?.let { tree.path(it.id) } ?: "sin grupo" },
        placeholder = "sin grupo",
    )
    Spacer(Modifier.height(TitanDimens.SpaceSm))
    if (creating) {
        NameEntryRow(
            label = "Nuevo grupo",
            onConfirm = { name ->
                onGroupId(controller.createGroup(scope, name).id)
                creating = false
            },
            onCancel = { creating = false },
        )
    } else {
        TitanButton("[+] Nuevo grupo", onClick = { creating = true }, kind = ButtonKind.SECONDARY)
    }
}
