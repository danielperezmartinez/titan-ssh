package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.clickable
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
import io.github.danielperezmartinez.titanssh.config.GroupScope
import io.github.danielperezmartinez.titanssh.config.GroupTree
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.terminal.AgentInsights
import io.github.danielperezmartinez.titanssh.terminal.AgentKey
import io.github.danielperezmartinez.titanssh.terminal.AgentManager
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/** Which inline confirmation a launcher row shows. */
private enum class LauncherConfirm { DELETE, TERMINATE }

/**
 * The launcher: saved sessions and the connection each resolves to. Tapping a
 * resolvable session (or its `[>] Lanzar`) opens a live tab; an unresolved one
 * is flagged and cannot be launched. The marker or a long press opens the
 * session's actions, one session at a time. The sessions are shown in the
 * folders of their groups ([[Carpetas de grupos en las listas]]). With no
 * sessions saved it offers a shortcut to Configuración's Sesiones tab.
 *
 * It also shows what the app last knew about each session's agent
 * ([[Estado y control del agente en la interfaz]]): a line on a session that is
 * alive on its destination without an open tab (in warning after 24 hours with
 * no client), a warning strip for an agent above 1 GB, and the actions to see
 * the agent and to terminate the session there.
 */
@Composable
internal fun Launcher(
    controller: ConfigController,
    agents: AgentManager,
    openSessionIds: Set<String>,
    onOpenAgent: (AgentHost) -> Unit,
    onLaunch: (ResolvedConnection) -> Unit,
    onEdit: (sessionId: String) -> Unit,
    onOpenConfig: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val config by controller.state.collectAsState()
    val watch by agents.watch.state.collectAsState()
    val now = rememberAppNow()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<LauncherConfirm?>(null) }
    val hosts = config.agentHosts()
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().padding(TitanDimens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton("[<]", onClick = onBack, color = TitanColors.Body)
                Text(
                    "Volver a las pestañas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TitanColors.Mute,
                    modifier = Modifier.padding(start = TitanDimens.SpaceSm),
                )
            }
            Hairline()
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
            val heavy = watch.observations.filter { (id, obs) -> id in hosts && AgentInsights.isOverMemory(obs) }
            items(heavy.entries.toList(), key = { "mem-${it.key}" }) { (id, obs) ->
                val host = hosts.getValue(id)
                MemoryStrip(host.key, obs.report.memoryBytes ?: 0) { onOpenAgent(host) }
                Hairline()
            }
            item {
                Caption("Lanza una sesión guardada para abrirla en una pestaña de terminal.")
                Spacer(Modifier.height(TitanDimens.SpaceMd))
                Hairline()
            }
            if (config.sessions.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState("No hay sesiones. Créalas en Configuración → Sesiones.")
                        TitanButton("[*] Ir a Configuración", onClick = onOpenConfig, kind = ButtonKind.PRIMARY)
                    }
                }
            }
            // In the folders of the sessions groups; the empty ones are left
            // out, and folding one here folds it in Configuración too.
            groupedRows(
                GroupTree(config.sessionGroups).rows(config.sessions, { it.groupId }, hideEmpty = true),
                itemKey = { it.id },
                folder = { folder ->
                    FolderRow(
                        folder,
                        count = folderCount(folder.itemCount, "sesión", "sesiones"),
                        onToggle = {
                            controller.setGroupCollapsed(GroupScope.SESSIONS, folder.group.id, !folder.group.collapsed)
                        },
                    )
                },
            ) { session ->
                val resolved = runCatching { config.resolve(session) }.getOrNull()
                val mousepad = session.type == SessionType.MOUSEPAD
                val subtitle = if (resolved != null) {
                    (if (mousepad) "mouse pad · " else "") +
                        "${resolved.endpoint.username}@${resolved.endpoint.host}:${resolved.endpoint.port}"
                } else if (config.hosts.any { it.id == session.hostId }) {
                    // The host is there, so its ProxyJump chain is what fails.
                    "ProxyJump sin resolver — revisa el bastión del host"
                } else {
                    "host no encontrado — revisa la configuración"
                }
                // A mouse pad keeps nothing alive in the agent's daemon.
                val isAgent = resolved != null && !mousepad && session.resilienceLevel == ResilienceLevel.AGENT
                val obs = resolved?.let { watch.observations[AgentKey.of(it.endpoint).id] }
                val live = obs?.let { AgentInsights.sessionOf(it, session.id) }
                val note = if (obs == null || live == null || session.id in openSessionIds) {
                    null
                } else if (AgentInsights.isAbandoned(obs, live, now)) {
                    val idle = AgentInsights.detachedForMs(obs, live, now) ?: 0
                    "sin conectar desde hace ${AgentInsights.formatDuration(idle)}" to TitanColors.Warning
                } else {
                    "viva en el destino · vista hace ${AgentInsights.formatDuration(now - obs.observedAtMs)}" to TitanColors.Mute
                }
                val expanded = expandedId == session.id
                val toggle = {
                    expandedId = if (expanded) null else session.id
                    confirming = null
                }
                ListRow(
                    marker = if (resolved != null) "[>]" else "[x]",
                    title = session.name,
                    subtitle = subtitle,
                    note = note?.first,
                    noteColor = note?.second ?: TitanColors.Mute,
                    // An unresolved session cannot connect, but its actions stay
                    // reachable so it can be fixed or removed from here.
                    onClick = resolved?.let { { onLaunch(it) } },
                    onLongClick = toggle,
                    onMarkerClick = toggle,
                    markerColor = when {
                        resolved == null -> TitanColors.Danger
                        expanded -> TitanColors.Accent
                        else -> TitanColors.Body
                    },
                    trailing = resolved?.let {
                        { TitanButton("[>] Lanzar", onClick = { onLaunch(it) }, kind = ButtonKind.SECONDARY) }
                    },
                    expanded = expanded,
                    expandedContent = {
                        ListRow(marker = "[~]", title = "Editar", onClick = { onEdit(session.id) })
                        ListRow(
                            marker = "[+]",
                            title = "Duplicar",
                            onClick = {
                                controller.duplicateSession(session.id)
                                expandedId = null
                            },
                        )
                        if (isAgent && resolved != null) {
                            ListRow(
                                marker = "[@]",
                                title = "Ver el agente del destino",
                                onClick = { onOpenAgent(agents.hostOf(resolved)) },
                            )
                        }
                        if (live != null) {
                            if (confirming == LauncherConfirm.TERMINATE) {
                                ConfirmRow(
                                    question = "¿Terminar en el destino?",
                                    subtitle = "Se cierra lo que tenga en marcha",
                                    confirmLabel = "[x] Sí",
                                    marker = "[x]",
                                    markerColor = TitanColors.Danger,
                                    onConfirm = {
                                        agents.terminate(session)
                                        expandedId = null
                                        confirming = null
                                    },
                                    onCancel = { confirming = null },
                                )
                            } else {
                                ListRow(
                                    marker = "[x]",
                                    title = "Terminar en el destino",
                                    markerColor = TitanColors.Danger,
                                    onClick = { confirming = LauncherConfirm.TERMINATE },
                                )
                            }
                        }
                        if (confirming == LauncherConfirm.DELETE) {
                            ConfirmRow(
                                question = "¿Eliminar la sesión?",
                                subtitle = if (live != null) "Sigue viva en el destino" else null,
                                confirmLabel = if (live != null) "[x] Eliminar y terminarla" else "[x] Sí",
                                marker = "[x]",
                                markerColor = TitanColors.Danger,
                                onConfirm = {
                                    agents.delete(session)
                                    expandedId = null
                                    confirming = null
                                },
                                onCancel = { confirming = null },
                            )
                        } else {
                            ListRow(
                                marker = "[x]",
                                title = "Eliminar",
                                markerColor = TitanColors.Danger,
                                onClick = { confirming = LauncherConfirm.DELETE },
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The warning strip for an agent over [AgentInsights.MEMORY_WARNING_BYTES]. */
@Composable
private fun MemoryStrip(key: AgentKey, bytes: Long, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = TitanDimens.SpaceMd, horizontal = TitanDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("[-]", style = MaterialTheme.typography.bodyLarge, color = TitanColors.Warning)
        Text(
            "El agente de ${key.label} ocupa ${AgentInsights.formatBytes(bytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = TitanColors.Warning,
            modifier = Modifier.weight(1f).padding(horizontal = TitanDimens.SpaceMd),
        )
        TitanButton("[>] Ver", onClick = onOpen, kind = ButtonKind.SECONDARY)
    }
}
