package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.danielperezmartinez.titanssh.config.Snippet
import io.github.danielperezmartinez.titanssh.secret.SecretProvisioner
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

private enum class ConfigTab(val label: String) {
    HOSTS("Hosts"),
    SESSIONS("Sesiones"),
    SNIPPETS("Snippets"),
    GROUPS("Grupos"),
}

private sealed interface Editor {
    data class HostEdit(val id: String?) : Editor
    data class SessionEdit(val id: String?) : Editor
    data class SnippetEdit(val id: String?) : Editor
}

/**
 * The "Configuración" area: manage and persist hosts, sessions, the snippet
 * library and groups. Lists route to full-screen editors; there is no Termius-
 * style layout (visual decision), just flat mono lists with ASCII markers.
 */
@Composable
fun ConfigArea(controller: ConfigController, provisioner: SecretProvisioner) {
    val config by controller.state.collectAsState()
    var tab by remember { mutableStateOf(ConfigTab.HOSTS) }
    var editor by remember { mutableStateOf<Editor?>(null) }

    when (val current = editor) {
        is Editor.HostEdit -> HostEditor(controller, current.id, provisioner) { editor = null }
        is Editor.SessionEdit -> SessionEditor(controller, current.id) { editor = null }
        is Editor.SnippetEdit -> SnippetEditor(controller, current.id) { editor = null }
        null -> Column(Modifier.fillMaxSize()) {
            SubTabBar(tab) { tab = it }
            Hairline()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    ConfigTab.HOSTS -> HostList(config.hosts, config.groups, onNew = { editor = Editor.HostEdit(null) }) {
                        editor = Editor.HostEdit(it.id)
                    }
                    ConfigTab.SESSIONS -> SessionList(config.sessions, config.hosts, onNew = { editor = Editor.SessionEdit(null) }) {
                        editor = Editor.SessionEdit(it.id)
                    }
                    ConfigTab.SNIPPETS -> SnippetList(config.snippets, onNew = { editor = Editor.SnippetEdit(null) }) {
                        editor = Editor.SnippetEdit(it.id)
                    }
                    ConfigTab.GROUPS -> GroupList(controller, config.groups)
                }
            }
        }
    }
}

@Composable
private fun SubTabBar(current: ConfigTab, onSelect: (ConfigTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceSm)) {
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

@Composable
private fun HostList(hosts: List<Host>, groups: List<Group>, onNew: () -> Unit, onOpen: (Host) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow("Nuevo host", onNew) }
        item { Hairline() }
        if (hosts.isEmpty()) {
            item { EmptyState("Sin hosts. Crea el primero con [+].") }
        }
        items(hosts) { host ->
            val group = groups.firstOrNull { it.id == host.groupId }?.name
            val subtitle = buildString {
                append("${host.username}@${host.hostname}:${host.port}")
                if (group != null) append("  ·  $group")
            }
            ListRow(marker = host.marker, title = host.alias.ifBlank { host.hostname }, subtitle = subtitle, onClick = { onOpen(host) })
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
private fun SnippetList(snippets: List<Snippet>, onNew: () -> Unit, onOpen: (Snippet) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
        item { NewRow("Nuevo snippet", onNew) }
        item { Hairline() }
        if (snippets.isEmpty()) {
            item { EmptyState("Biblioteca de snippets vacía.") }
        }
        items(snippets) { snippet ->
            ListRow(marker = "[>]", title = snippet.name, subtitle = snippet.body.take(60), onClick = { onOpen(snippet) }, markerColor = TitanColors.Mute)
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
                onClick = {},
                markerColor = TitanColors.Body,
                trailing = { GlyphButton("[x]", onClick = { controller.deleteGroup(group.id) }, color = TitanColors.Danger) },
            )
            Hairline()
        }
    }
}
