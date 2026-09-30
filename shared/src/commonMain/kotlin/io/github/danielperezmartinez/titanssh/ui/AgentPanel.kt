package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.danielperezmartinez.titanssh.BuildInfo
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.ResolvedConnection
import io.github.danielperezmartinez.titanssh.config.TitanConfig
import io.github.danielperezmartinez.titanssh.config.resolve
import io.github.danielperezmartinez.titanssh.formatLocalDateTime
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.terminal.AgentInsights
import io.github.danielperezmartinez.titanssh.terminal.AgentManager
import io.github.danielperezmartinez.titanssh.terminal.AgentObservation
import io.github.danielperezmartinez.titanssh.terminal.AgentPreview
import io.github.danielperezmartinez.titanssh.terminal.AgentSessionReport
import io.github.danielperezmartinez.titanssh.terminal.AgentStatusReport
import io.github.danielperezmartinez.titanssh.terminal.AgentTransport
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** The app's clock in epoch milliseconds, ticking once a minute for "hace X". */
@Composable
internal fun rememberAppNow(): Long {
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(60_000)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

/**
 * Every agent the config can reach, by [AgentKey.id][io.github.danielperezmartinez.titanssh.terminal.AgentKey.id]:
 * each host's default user and each session's resolved user.
 */
internal fun TitanConfig.agentHosts(): Map<String, AgentHost> {
    val byHost = hosts.map { AgentHost.of(it) }
    val bySession = sessions.mapNotNull { s ->
        runCatching { resolve(s) }.getOrNull()?.let { AgentHost(it.endpoint, it.auth, it.host.keepAliveSeconds) }
    }
    return (byHost + bySession).associateBy { it.key.id }
}

/** Which inline confirmation the panel shows. */
private sealed interface PanelConfirm {
    data class Terminate(val agentSessionId: String) : PanelConfirm
    data object Stop : PanelConfirm
    data object Update : PanelConfirm
}

/**
 * The agent panel ([[Estado y control del agente en la interfaz]]): what the
 * agent of [host] holds and the explicit ways to close it. Opening it queries
 * the destination; the data shown is always that of the last query, with how
 * long ago it was made.
 */
@Composable
fun AgentPanel(
    agents: AgentManager,
    controller: ConfigController,
    host: AgentHost,
    onBack: () -> Unit,
    onOpenSession: (ResolvedConnection) -> Unit,
) {
    val config by controller.state.collectAsState()
    val watch by agents.watch.state.collectAsState()
    val errors by agents.watch.errors.collectAsState()
    val busy by agents.busy.collectAsState()
    val allPreviews by agents.previews.collectAsState()
    val now = rememberAppNow()
    val key = host.key
    val obs = watch.observations[key.id]
    val previews = allPreviews[key.id].orEmpty()
    val pending = watch.pendingClose[key.id].orEmpty()
    val isBusy = key.id in busy
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<PanelConfirm?>(null) }

    LaunchedEffect(key.id) { agents.refreshWithPreviews(host) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Agente · ${key.label}", onBack)
        Hairline()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
            item {
                PanelHeader(obs, now)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(
                        when {
                            isBusy -> "Consultando el destino…"
                            obs == null -> "Aún no se ha consultado este agente"
                            else -> "consultado hace ${AgentInsights.formatDuration(now - obs.observedAtMs)}"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TitanButton("[>] Consultar", onClick = { agents.refreshWithPreviews(host) }, kind = ButtonKind.SECONDARY, enabled = !isBusy)
                }
                errors[key.id]?.let { Caption("[x] $it", color = TitanColors.Danger) }
                Spacer(Modifier.height(TitanDimens.SpaceMd))
                Hairline()
            }
            val report = obs?.report
            if (report != null && AgentInsights.isOutdated(report, BuildInfo.VERSION) && report.state != AgentStatusReport.STATE_UNREACHABLE) {
                item {
                    if (confirming == PanelConfirm.Update) {
                        ConfirmRow(
                            question = "¿Actualizar el agente?",
                            subtitle = "Se cierran todas sus sesiones",
                            confirmLabel = "[x] Sí",
                            marker = "[-]",
                            markerColor = TitanColors.Warning,
                            onConfirm = {
                                agents.stopAgent(host)
                                confirming = null
                            },
                            onCancel = { confirming = null },
                        )
                    } else {
                        ListRow(
                            marker = "[-]",
                            markerColor = TitanColors.Warning,
                            title = "Agente de otra versión: ${report.agent ?: "?"}",
                            subtitle = if (report.state == AgentStatusReport.STATE_LEGACY) {
                                "No informa de sus sesiones ni permite cerrarlas una a una"
                            } else {
                                "La app es la ${BuildInfo.VERSION}"
                            },
                            trailing = {
                                TitanButton("[>] Actualizar", onClick = { confirming = PanelConfirm.Update }, kind = ButtonKind.SECONDARY)
                            },
                        )
                    }
                    Hairline()
                }
            }
            val shown = report?.sessions.orEmpty()
            // Recoverable ones; a session whose shell ended is shown until the
            // daemon drops it, but cannot be opened or terminated.
            val listed = shown.filter { !it.closed }
            items(shown, key = { it.id }) { s ->
                val saved = config.sessions.firstOrNull { AgentTransport.sanitizeId(it.id) == s.id }
                val resolved = saved?.let { runCatching { config.resolve(it) }.getOrNull() }
                val expanded = expandedId == s.id
                val toggle = {
                    expandedId = if (expanded) null else s.id
                    confirming = null
                }
                val (marker, color) = sessionMarker(obs, s, now)
                ListRow(
                    marker = marker,
                    markerColor = if (expanded) TitanColors.Accent else color,
                    title = saved?.name ?: "(sesión borrada)",
                    subtitle = sessionLine(obs, s, now, orphan = saved == null, pending = s.id in pending),
                    onClick = toggle,
                    onLongClick = toggle,
                    onMarkerClick = toggle,
                    expanded = expanded,
                    expandedContent = {
                        if (obs != null) SessionDetails(obs, s, previews[s.id], now, orphan = saved == null)
                        if (s.closed) return@ListRow
                        if (resolved != null) {
                            ListRow(marker = "[>]", title = "Abrir", onClick = { onOpenSession(resolved) })
                        }
                        if (confirming == PanelConfirm.Terminate(s.id)) {
                            ConfirmRow(
                                question = "¿Terminar la sesión?",
                                subtitle = "Se cierra lo que tenga en marcha",
                                confirmLabel = "[x] Sí",
                                marker = "[x]",
                                markerColor = TitanColors.Danger,
                                onConfirm = {
                                    if (saved != null) agents.terminate(saved) else agents.closeAgentSession(host, s.id)
                                    expandedId = null
                                    confirming = null
                                },
                                onCancel = { confirming = null },
                            )
                        } else {
                            ListRow(
                                marker = "[x]",
                                title = "Terminar",
                                markerColor = TitanColors.Danger,
                                onClick = { confirming = PanelConfirm.Terminate(s.id) },
                            )
                        }
                    },
                )
                Hairline()
            }
            // Deleted sessions whose close has not reached the destination yet
            // and that the last report does not list (it may predate them).
            val unlisted = pending.filter { id -> listed.none { it.id == id } }
            items(unlisted, key = { "pending-$it" }) {
                ListRow(marker = "[-]", markerColor = TitanColors.Mute, title = "(sesión borrada)", subtitle = "pendiente de terminar")
                Hairline()
            }
            if (report?.isRunning == true && shown.isEmpty() && unlisted.isEmpty()) {
                item { EmptyState("El agente no guarda ninguna sesión.") }
            }
            if (report != null && (report.isRunning || report.state == AgentStatusReport.STATE_LEGACY)) {
                item {
                    Spacer(Modifier.height(TitanDimens.SpaceMd))
                    val count = if (report.isRunning && listed.isNotEmpty()) " (cerrará ${sessionsLabel(listed.size)})" else ""
                    if (confirming == PanelConfirm.Stop) {
                        ConfirmRow(
                            question = "¿Detener el agente?",
                            subtitle = "Se cierran todas sus sesiones",
                            confirmLabel = "[x] Sí",
                            marker = "[x]",
                            markerColor = TitanColors.Danger,
                            onConfirm = {
                                agents.stopAgent(host)
                                confirming = null
                            },
                            onCancel = { confirming = null },
                        )
                    } else {
                        ListRow(
                            marker = "[x]",
                            markerColor = TitanColors.Danger,
                            title = "Detener el agente$count",
                            onClick = { confirming = PanelConfirm.Stop },
                        )
                    }
                }
            }
        }
    }
}

/** The agent's own lines: version, uptime and memory, or why there is nothing to show. */
@Composable
private fun PanelHeader(obs: AgentObservation?, now: Long) {
    val report = obs?.report ?: return
    val text = when (report.state) {
        AgentStatusReport.STATE_RUNNING -> buildString {
            append("v${report.agent}")
            report.startedMs?.let { append(" · encendido hace ${AgentInsights.formatDuration(AgentInsights.agentNow(obs, now) - it)}") }
            report.memoryBytes?.let { append(" · ${AgentInsights.formatBytes(it)}") }
        }
        AgentStatusReport.STATE_STOPPED -> "No hay ningún agente en marcha en este destino."
        AgentStatusReport.STATE_LEGACY -> "v${report.agent ?: "?"} · PID ${report.pid ?: "?"}"
        else -> "Hay un agente que no responde. Ciérralo a mano en el destino: " +
            "es el proceso agent-${report.agent ?: "<versión>"}-… del usuario" +
            (report.pid?.let { " (PID $it)" } ?: "") + "."
    }
    val color: Color = if (report.state == AgentStatusReport.STATE_UNREACHABLE) TitanColors.Warning else TitanColors.Body
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(vertical = TitanDimens.SpaceSm))
}

/**
 * The unfolded detail of one session ([[Detalle de las sesiones en el panel
 * del agente]]): a line per fact, then the preview of its terminal.
 */
@Composable
private fun SessionDetails(obs: AgentObservation, s: AgentSessionReport, preview: AgentPreview?, now: Long, orphan: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = TitanDimens.SpaceSm, horizontal = TitanDimens.SpaceXs)) {
        AgentInsights.sessionDetails(obs, s, now, orphan) { formatLocalDateTime(it, now) }.forEachIndexed { i, line ->
            Caption(line, color = if (i == 0 && !s.closed) TitanColors.Body else TitanColors.Mute)
        }
        val lines = preview?.lines().orEmpty()
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            Caption("vista previa")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = TitanDimens.SpaceXs, end = TitanDimens.SpaceSm)
                    .background(TitanColors.TerminalBg)
                    .horizontalScroll(rememberScrollState())
                    .padding(TitanDimens.SpaceSm),
            ) {
                lines.forEach { line ->
                    Text(line.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall, color = TitanColors.Body, softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

private fun sessionMarker(obs: AgentObservation?, s: AgentSessionReport, now: Long): Pair<String, Color> = when {
    s.closed -> "[x]" to TitanColors.Mute
    s.clients > 0 -> "[+]" to TitanColors.Success
    obs != null && AgentInsights.isAbandoned(obs, s, now) -> "[-]" to TitanColors.Warning
    else -> "[-]" to TitanColors.Mute
}

private fun sessionLine(obs: AgentObservation?, s: AgentSessionReport, now: Long, orphan: Boolean, pending: Boolean): String {
    if (pending) return "pendiente de terminar"
    if (s.closed) return "la shell terminó · no se puede recuperar"
    val idle = when {
        s.clients > 0 -> "conectada ahora"
        obs != null && AgentInsights.isAbandoned(obs, s, now) ->
            "sin conectar desde hace ${AgentInsights.formatDuration(AgentInsights.detachedForMs(obs, s, now) ?: 0)}"
        obs != null -> "último uso hace ${AgentInsights.formatDuration(AgentInsights.agentNow(obs, now) - s.lastUsedMs)}"
        else -> null
    }
    val state = when {
        orphan -> "huérfana"
        s.clients == 0 && obs != null && !AgentInsights.isAbandoned(obs, s, now) -> "en segundo plano"
        else -> null
    }
    val memory = s.memoryBytes?.let { AgentInsights.formatBytes(it) }
    // What runs in it first: what most tells two sessions apart.
    return listOfNotNull(s.foreground, state, idle, memory).joinToString(" · ")
}

private fun sessionsLabel(n: Int) = if (n == 1) "1 sesión" else "$n sesiones"
