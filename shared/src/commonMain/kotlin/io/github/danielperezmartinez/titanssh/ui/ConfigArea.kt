package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import io.github.danielperezmartinez.titanssh.config.Host
import io.github.danielperezmartinez.titanssh.config.Ids
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
    GROUPS("Grupos"),
}

private sealed interface Editor {
    data class HostEdit(val id: String?) : Editor
    data class SessionEdit(val id: String?) : Editor
    data class LibraryScriptEdit(val id: String?) : Editor
}

/**
 * The "Configuración" area: manage and persist hosts, sessions, the script
 * library and groups. Lists route to full-screen editors; there is no Termius-
 * style layout (visual decision), just flat mono lists with ASCII markers.
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
        is Editor.HostEdit -> HostEditor(controller, current.id, provisioner) { editor = null }
        is Editor.SessionEdit -> SessionEditor(controller, current.id, onDone = closeEditor, deleteSession = onDeleteSession)
        is Editor.LibraryScriptEdit -> LibraryScriptEditor(controller, current.id) { editor = null }
        null -> Column(Modifier.fillMaxSize()) {
            TopBar("Configuración", onBack)
            Hairline()
            SubTabBar(tab) { tab = it }
            Hairline()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    ConfigTab.HOSTS -> HostList(
                        config.hosts,
                        config.groups,
                        onNew = { editor = Editor.HostEdit(null) },
                        onOpenAgent = onOpenAgent,
                    ) {
                        editor = Editor.HostEdit(it.id)
                    }
                    ConfigTab.SESSIONS -> SessionList(config.sessions, config.hosts, onNew = { editor = Editor.SessionEdit(null) }) {
                        editor = Editor.SessionEdit(it.id)
                    }
                    ConfigTab.SCRIPTS -> LibraryScriptList(config, onNew = { editor = Editor.LibraryScriptEdit(null) }) {
                        editor = Editor.LibraryScriptEdit(it.id)
                    }
                    ConfigTab.GROUPS -> GroupList(controller, config.groups)
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
 * The hosts. Tapping one edits it; its marker or a long press opens its
 * actions: edit and, with [onOpenAgent], see the agent of its default user.
 */
@Composable
private fun HostList(
    hosts: List<Host>,
    groups: List<Group>,
    onNew: () -> Unit,
    onOpenAgent: ((AgentHost) -> Unit)?,
    onOpen: (Host) -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow("Nuevo host", onNew) }
        item { Hairline() }
        if (hosts.isEmpty()) {
            item { EmptyState("Sin hosts. Crea el primero con [+].") }
        }
        items(hosts, key = { it.id }) { host ->
            val group = groups.firstOrNull { it.id == host.groupId }?.name
            val subtitle = buildString {
                append("${host.username}@${host.hostname}:${host.port}")
                if (group != null) append("  ·  $group")
            }
            val expanded = expandedId == host.id
            val toggle = { expandedId = if (expanded) null else host.id }
            ListRow(
                marker = host.marker,
                markerColor = if (expanded) TitanColors.Accent else TitanColors.Body,
                title = host.alias.ifBlank { host.hostname },
                subtitle = subtitle,
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
            Hairline()
        }
    }
}

@Composable
private fun SessionList(sessions: List<Session>, hosts: List<Host>, onNew: () -> Unit, onOpen: (Session) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow("Nueva sesión", onNew) }
        item { Hairline() }
        if (sessions.isEmpty()) {
            item { EmptyState("Sin sesiones. Crea una que reutilice un host.") }
        }
        items(sessions) { session ->
            val host = hosts.firstOrNull { it.id == session.hostId }
            val hostLabel = host?.alias?.ifBlank { host.hostname } ?: "host desconocido"
            val scripts = session.scripts.size
            val subtitle = "$hostLabel  ·  ${scripts} script(s)  ·  ${session.resilienceLevel.name.lowercase()}"
            // Neutral marker for a saved session; danger only flags the real error
            // state of a dangling host reference.
            val markerColor = if (host == null) TitanColors.Danger else TitanColors.Body
            ListRow(marker = if (host == null) "[x]" else session.marker, title = session.name, subtitle = subtitle, onClick = { onOpen(session) }, markerColor = markerColor)
            Hairline()
        }
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

@Composable
private fun GroupList(controller: ConfigController, groups: List<Group>) {
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                Box(Modifier.weight(1f)) {
                    TitanTextField(label = "Nuevo grupo (proyecto)", value = name, onValueChange = { name = it }, placeholder = "p. ej. cliente-x")
                }
                TitanButton("[+] Añadir", onClick = {
                    if (name.isNotBlank()) {
                        controller.upsertGroup(Group(id = Ids.group(), name = name.trim()))
                        name = ""
                    }
                }, kind = ButtonKind.PRIMARY)
            }
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            Hairline()
        }
        if (groups.isEmpty()) {
            item { EmptyState("Sin grupos. Agrupa hosts y sesiones por proyecto.") }
        }
        items(groups) { group ->
            ListRow(
                marker = "[#]",
                title = group.name,
                subtitle = null,
                markerColor = TitanColors.Body,
                trailing = { GlyphButton("[x]", onClick = { controller.deleteGroup(group.id) }, color = TitanColors.Danger) },
            )
            Hairline()
        }
    }
}
