package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.LibraryScript
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.sessionsUsing
import io.github.danielperezmartinez.titanssh.secret.SecretProvisioner
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

private enum class ConfigTab(val label: String) {
    HOSTS("Hosts"),
    SESSIONS("Sesiones"),
    SCRIPTS("Scripts"),
}

private sealed interface Editor {
    /** [groupId]: the group a new host starts in. */
    data class HostEdit(val id: String?, val groupId: String? = null) : Editor
    data class SessionEdit(val id: String?, val groupId: String? = null) : Editor
    data class LibraryScriptEdit(val id: String?) : Editor
}

/**
 * The "Configuración" area: manage and persist hosts, sessions and the script
 * library. Lists route to full-screen editors; there is no Termius-style layout
 * (visual decision), just flat mono lists with ASCII markers. Hosts and
 * sessions are shown in the folders of their own groups, which are created and
 * managed right in those lists ([[Grupos de hosts y de sesiones como carpetas]]).
 * Opened from the header's `[*]`; [onBack] returns to Sesiones. With
 * [editSessionId] it opens straight on that session's editor (the launcher's
 * "Editar"), and closing that editor returns to Sesiones too. With
 * [openOnSessions] it starts on the Sesiones tab (the empty launcher's shortcut).
 */
@Composable
fun ConfigArea(
    controller: ConfigController,
    provisioner: SecretProvisioner,
    onBack: () -> Unit,
    editSessionId: String? = null,
    openOnSessions: Boolean = false,
    /** Deletes a saved session; the app also terminates it on its destination. */
    onDeleteSession: (Session) -> Unit = { controller.deleteSession(it.id) },
    /** Opens the panel of a host's agent (its default user). */
    onOpenAgent: ((AgentHost) -> Unit)? = null,
) {
    val config by controller.state.collectAsState()
    var tab by remember {
        mutableStateOf(if (editSessionId != null || openOnSessions) ConfigTab.SESSIONS else ConfigTab.HOSTS)
    }
    var editor by remember { mutableStateOf<Editor?>(editSessionId?.let { Editor.SessionEdit(it) }) }
    val closeEditor = { if (editSessionId != null) onBack() else editor = null }

    when (val current = editor) {
        is Editor.HostEdit -> HostEditor(controller, current.id, provisioner, initialGroupId = current.groupId) { editor = null }
        is Editor.SessionEdit -> SessionEditor(
            controller,
            current.id,
            onDone = closeEditor,
            deleteSession = onDeleteSession,
            initialGroupId = current.groupId,
        )
        is Editor.LibraryScriptEdit -> LibraryScriptEditor(controller, current.id) { editor = null }
        null -> Column(Modifier.fillMaxSize()) {
            TopBar("Configuración", onBack)
            Hairline()
            SubTabBar(tab) { tab = it }
            Hairline()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    ConfigTab.HOSTS -> HostList(
                        controller,
                        config,
                        onNew = { groupId -> editor = Editor.HostEdit(null, groupId) },
                        onOpenAgent = onOpenAgent,
                    ) {
                        editor = Editor.HostEdit(it.id)
                    }
                    ConfigTab.SESSIONS -> SessionList(
                        controller,
                        config,
                        onNew = { groupId -> editor = Editor.SessionEdit(null, groupId) },
                    ) {
                        editor = Editor.SessionEdit(it.id)
                    }
                    ConfigTab.SCRIPTS -> LibraryScriptList(config, onNew = { editor = Editor.LibraryScriptEdit(null) }) {
                        editor = Editor.LibraryScriptEdit(it.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubTabBar(current: ConfigTab, onSelect: (ConfigTab) -> Unit) {
    // Scrolls sideways when the tabs do not fit (narrow screen, large system
    // font) instead of squeezing a label onto two lines.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TitanDimens.SpaceSm),
    ) {
        ConfigTab.entries.forEach { entry ->
            val selected = entry == current
            Box(
                Modifier
                    .clickable { onSelect(entry) }
                    .background(if (selected) TitanColors.Surface else TitanColors.Canvas)
                    .height(TitanDimens.TouchTarget)
                    .padding(horizontal = TitanDimens.SpaceMd),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) TitanColors.Ink else TitanColors.Mute,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun NewRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = TitanDimens.SpaceMd, horizontal = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("[+]", style = MaterialTheme.typography.bodyLarge, color = TitanColors.Accent)
        Spacer(Modifier.height(TitanDimens.SpaceMd))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TitanColors.Accent, modifier = Modifier.padding(start = TitanDimens.SpaceMd))
    }
}

/**
 * A list of hosts or sessions shown in the folders of [scope]'s groups, with
 * the rows to create an entry and a group at the top. A folder folds or unfolds
 * with a tap and remembers it; its marker or a long press opens its actions
 * ([GroupActions]). One row of the list shows its actions at a time: [entry]
 * gets the open row's key and a way to change it (`"i:<id>"` for an entry).
 */
@Composable
private fun <T> GroupedConfigList(
    controller: ConfigController,
    scope: GroupScope,
    groups: List<Group>,
    entries: List<T>,
    groupOf: (T) -> String?,
    idOf: (T) -> String,
    newLabel: String,
    newInGroupLabel: String,
    emptyText: String,
    countSingular: String,
    countPlural: String,
    onNew: (groupId: String?) -> Unit,
    entry: @Composable (T, openKey: String?, setOpenKey: (String?) -> Unit) -> Unit,
) {
    var openKey by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf<GroupStep?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    val setOpenKey: (String?) -> Unit = { key ->
        openKey = key
        step = null
    }
    val tree = GroupTree(groups)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow(newLabel) { onNew(null) } }
        item {
            if (creatingGroup) {
                NameEntryRow(
                    label = "Nuevo grupo",
                    onConfirm = { name ->
                        controller.createGroup(scope, name)
                        creatingGroup = false
                    },
                    onCancel = { creatingGroup = false },
                )
            } else {
                NewRow("Nuevo grupo") { creatingGroup = true }
            }
        }
        item { Hairline() }
        if (entries.isEmpty() && groups.isEmpty()) {
            item { EmptyState(emptyText) }
        }
        groupedRows(
            tree.rows(entries, groupOf),
            itemKey = { "item-${idOf(it)}" },
            folder = { folder ->
                val key = "g:${folder.group.id}"
                val open = openKey == key
                FolderRow(
                    folder,
                    count = folderCount(folder.itemCount, countSingular, countPlural),
                    onToggle = { controller.setGroupCollapsed(scope, folder.group.id, !folder.group.collapsed) },
                    onActions = { setOpenKey(if (open) null else key) },
                    actionsOpen = open,
                    actions = {
                        GroupActions(
                            controller,
                            scope,
                            tree,
                            folder,
                            step = step,
                            onStep = { step = it },
                            newEntryLabel = newInGroupLabel,
                            onNewEntry = {
                                setOpenKey(null)
                                onNew(folder.group.id)
                            },
                            onClose = { setOpenKey(null) },
                        )
                    },
                )
            },
            item = { value -> entry(value, openKey, setOpenKey) },
        )
    }
}

/**
 * The hosts, in the folders of the hosts groups. Tapping one edits it; its
 * marker or a long press opens its actions: edit and, with [onOpenAgent], see
 * the agent of its default user.
 */
@Composable
private fun HostList(
    controller: ConfigController,
    config: TitanConfig,
    onNew: (groupId: String?) -> Unit,
    onOpenAgent: ((AgentHost) -> Unit)?,
    onOpen: (Host) -> Unit,
) {
    GroupedConfigList(
        controller,
        GroupScope.HOSTS,
        config.hostGroups,
        config.hosts,
        groupOf = { it.groupId },
        idOf = { it.id },
        newLabel = "Nuevo host",
        newInGroupLabel = "Nuevo host en este grupo",
        emptyText = "Sin hosts. Crea el primero con [+].",
        countSingular = "host",
        countPlural = "hosts",
        onNew = onNew,
    ) { host, openKey, setOpenKey ->
        val key = "i:${host.id}"
        val expanded = openKey == key
        val toggle = { setOpenKey(if (expanded) null else key) }
        ListRow(
            marker = host.marker,
            markerColor = if (expanded) TitanColors.Accent else TitanColors.Body,
            title = host.alias.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}:${host.port}",
            onClick = { onOpen(host) },
            onLongClick = onOpenAgent?.let { toggle },
            onMarkerClick = onOpenAgent?.let { toggle },
            expanded = expanded,
            expandedContent = onOpenAgent?.let { open ->
                {
                    ListRow(marker = "[~]", title = "Editar", onClick = { onOpen(host) })
                    ListRow(marker = "[@]", title = "Ver el agente del destino", onClick = { open(AgentHost.of(host)) })
                }
            },
        )
    }
}

/** The sessions, in the folders of the sessions groups. Tapping one edits it. */
@Composable
private fun SessionList(
    controller: ConfigController,
    config: TitanConfig,
    onNew: (groupId: String?) -> Unit,
    onOpen: (Session) -> Unit,
) {
    GroupedConfigList(
        controller,
        GroupScope.SESSIONS,
        config.sessionGroups,
        config.sessions,
        groupOf = { it.groupId },
        idOf = { it.id },
        newLabel = "Nueva sesión",
        newInGroupLabel = "Nueva sesión en este grupo",
        emptyText = "Sin sesiones. Crea una que reutilice un host.",
        countSingular = "sesión",
        countPlural = "sesiones",
        onNew = onNew,
    ) { session, _, _ ->
        val host = config.hosts.firstOrNull { it.id == session.hostId }
        val hostLabel = host?.alias?.ifBlank { host.hostname } ?: "host desconocido"
        val scripts = session.scripts.size
        val subtitle = "$hostLabel  ·  ${scripts} script(s)  ·  ${session.resilienceLevel.name.lowercase()}"
        // Neutral marker for a saved session; danger only flags the real error
        // state of a dangling host reference.
        val markerColor = if (host == null) TitanColors.Danger else TitanColors.Body
        ListRow(marker = if (host == null) "[x]" else session.marker, title = session.name, subtitle = subtitle, onClick = { onOpen(session) }, markerColor = markerColor)
    }
}

@Composable
private fun LibraryScriptList(config: TitanConfig, onNew: () -> Unit, onOpen: (LibraryScript) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow("Nuevo script", onNew) }
        item { Hairline() }
        if (config.scripts.isEmpty()) {
            item { EmptyState("Biblioteca de scripts vacía. Úsalos desde cualquier sesión.") }
        }
        items(config.scripts) { script ->
            val uses = config.sessionsUsing(script.id).size
            val subtitle = script.body.lineSequence().firstOrNull().orEmpty().take(60) +
                if (uses > 0) "  ·  en $uses sesión(es)" else ""
            ListRow(marker = "[>]", title = script.name, subtitle = subtitle, onClick = { onOpen(script) }, markerColor = TitanColors.Mute)
            Hairline()
        }
    }
}
