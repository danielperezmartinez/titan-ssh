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
import io.github.danielperezmartinez.titanssh.config.ReopenMode
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.terminal.AgentInsights
import io.github.danielperezmartinez.titanssh.terminal.AgentKey
import io.github.danielperezmartinez.titanssh.terminal.AgentManager
import io.github.danielperezmartinez.titanssh.terminal.LaunchPlan
import io.github.danielperezmartinez.titanssh.terminal.SessionInstances
import io.github.danielperezmartinez.titanssh.terminal.SessionManager
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/** Which inline confirmation a launcher row shows. */
private enum class LauncherConfirm { DELETE, TERMINATE }

/** What an unfolded launcher row shows: the session's actions, or where to open it (ADR-0020). */
private sealed interface Unfolded {
    val sessionId: String

    data class Actions(override val sessionId: String) : Unfolded

    data class Choice(override val sessionId: String, val plan: LaunchPlan.Ask) : Unfolded
}

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
 *
 * Launching a session that already has instances open in tabs or alive on its
 * destination follows its [ReopenMode] (ADR-0020): by default the row unfolds
 * the choice between re-attaching to each instance and a new parallel one.
 */
@Composable
internal fun Launcher(
    controller: ConfigController,
    agents: AgentManager,
    /** The instances open in tabs, by saved session id ([SessionManager.openInstances]). */
    openInstances: Map<String, Set<Int>>,
    onOpenAgent: (AgentHost) -> Unit,
    /** Opens [instance] of a session in a tab; [mustBeNew] for a new parallel one. */
    onLaunch: (ResolvedConnection, instance: Int, mustBeNew: Boolean) -> Unit,
    onEdit: (sessionId: String) -> Unit,
    onOpenConfig: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val config by controller.state.collectAsState()
    val watch by agents.watch.state.collectAsState()
    val now = rememberAppNow()
    var unfolded by remember { mutableStateOf<Unfolded?>(null) }
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
                val subtitle = if (resolved != null) {
                    when (session.type) {
                        SessionType.TERMINAL -> ""
                        SessionType.MOUSEPAD -> "mouse pad · "
                        SessionType.SFTP -> "sftp · "
                    } +
                        "${resolved.endpoint.username}@${resolved.endpoint.host}:${resolved.endpoint.port}"
                } else if (config.hosts.any { it.id == session.hostId }) {
                    // The host is there, so its ProxyJump chain is what fails.
                    "ProxyJump sin resolver — revisa el bastión del host"
                } else {
                    "host no encontrado — revisa la configuración"
                }
                // A mouse pad or an SFTP browser keeps nothing alive in the agent's daemon.
                val isAgent = resolved != null && session.type == SessionType.TERMINAL &&
                    session.resilienceLevel == ResilienceLevel.AGENT
                val obs = resolved?.let { watch.observations[AgentKey.of(it.endpoint).id] }
                // Every instance alive on the destination (ADR-0020), and those
                // of them without a tab here.
                val live = obs?.let { AgentInsights.instancesOf(it, session.id) }.orEmpty()
                val inTabs = openInstances[session.id].orEmpty()
                val background = live.filterKeys { it !in inTabs }.values
                val note = if (obs == null || background.isEmpty()) {
                    null
                } else if (background.any { AgentInsights.isAbandoned(obs, it, now) }) {
                    val idle = background.maxOf { AgentInsights.detachedForMs(obs, it, now) ?: 0 }
                    "sin conectar desde hace ${AgentInsights.formatDuration(idle)}" to TitanColors.Warning
                } else {
                    val what = if (background.size > 1) "${background.size} vivas en el destino" else "viva en el destino"
                    "$what · vista hace ${AgentInsights.formatDuration(now - obs.observedAtMs)}" to TitanColors.Mute
                }
                val launch = { r: ResolvedConnection ->
                    when (val plan = SessionInstances.plan(session, inTabs, live.keys)) {
                        is LaunchPlan.Open -> onLaunch(r, plan.instance, plan.mustBeNew)
                        is LaunchPlan.Ask -> {
                            val choice = Unfolded.Choice(session.id, plan)
                            unfolded = if (unfolded == choice) null else choice
                            confirming = null
                        }
                    }
                }
                val expanded = unfolded?.sessionId == session.id
                val toggle = {
                    unfolded = if (unfolded is Unfolded.Actions && expanded) null else Unfolded.Actions(session.id)
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
                    onClick = resolved?.let { { launch(it) } },
                    onLongClick = toggle,
                    onMarkerClick = toggle,
                    markerColor = when {
                        resolved == null -> TitanColors.Danger
                        expanded -> TitanColors.Accent
                        else -> TitanColors.Body
                    },
                    trailing = resolved?.let {
                        { TitanButton("[>] Lanzar", onClick = { launch(it) }, kind = ButtonKind.SECONDARY) }
                    },
                    expanded = expanded,
                    expandedContent = expandedContent@{
                        val choice = unfolded as? Unfolded.Choice
                        if (choice != null && resolved != null) {
                            val names = live.mapNotNull { (n, s) -> s.name?.takeIf { it.isNotBlank() }?.let { n to it } }.toMap()
                            ReopenChoice(session.name, choice.plan, names) { instance, mustBeNew ->
                                unfolded = null
                                onLaunch(resolved, instance, mustBeNew)
                            }
                            return@expandedContent
                        }
                        EntryActions(
                            onEdit = { onEdit(session.id) },
                            onDuplicate = {
                                controller.duplicateSession(session.id)
                                unfolded = null
                            },
                            confirmingDelete = confirming == LauncherConfirm.DELETE,
                            onConfirmingDelete = { confirming = if (it) LauncherConfirm.DELETE else null },
                            onDelete = {
                                agents.delete(session)
                                unfolded = null
                                confirming = null
                            },
                            deleteQuestion = "¿Eliminar la sesión?",
                            deleteSubtitle = if (live.isNotEmpty()) "Sigue viva en el destino" else null,
                            deleteConfirmLabel = if (live.isNotEmpty()) "[x] Eliminar y terminarla" else "[x] Sí",
                        ) {
                            if (isAgent && resolved != null) {
                                ListRow(
                                    marker = "[@]",
                                    title = "Ver el agente del destino",
                                    onClick = { onOpenAgent(agents.hostOf(resolved)) },
                                )
                            }
                            if (live.isNotEmpty()) {
                                if (confirming == LauncherConfirm.TERMINATE) {
                                    ConfirmRow(
                                        question = "¿Terminar en el destino?",
                                        subtitle = if (live.size > 1) "Se cierra lo que tengan en marcha sus ${live.size} sesiones" else "Se cierra lo que tenga en marcha",
                                        confirmLabel = "[x] Sí",
                                        marker = "[x]",
                                        markerColor = TitanColors.Danger,
                                        onConfirm = {
                                            agents.terminate(session)
                                            unfolded = null
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
                        }
                    },
                )
            }
        }
    }
}

/**
 * Where to open a session that already has instances (ADR-0020): re-attach to
 * one of them, or a new parallel one. Rows like the session's actions.
 */
@Composable
private fun ReopenChoice(
    name: String,
    plan: LaunchPlan.Ask,
    /** The names the user gave instances (kept by the agent), by instance. */
    names: Map<Int, String>,
    onPick: (instance: Int, mustBeNew: Boolean) -> Unit,
) {
    Caption("Ya está abierta. Elige dónde entrar.", modifier = Modifier.padding(vertical = TitanDimens.SpaceXs))
    plan.options.forEach { option ->
        ListRow(
            marker = "[>]",
            title = if (option.instance == SessionInstances.PRINCIPAL && names[option.instance] == null) {
                "Reengancharse a la principal"
            } else {
                "Reengancharse a ${SessionInstances.label(name, option.instance, names[option.instance])}"
            },
            subtitle = when {
                option.openInTab -> "abierta en una pestaña"
                option.live -> "viva en el destino, sin pestaña"
                else -> "sin pestaña abierta"
            },
            onClick = { onPick(option.instance, false) },
        )
    }
    ListRow(
        marker = "[+]",
        title = "Nueva sesión paralela",
        subtitle = "una terminal nueva: ${SessionInstances.label(name, plan.nextParallel)}",
        markerColor = TitanColors.Accent,
        onClick = { onPick(plan.nextParallel, true) },
    )
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
