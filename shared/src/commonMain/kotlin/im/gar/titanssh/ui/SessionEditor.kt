package im.gar.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.Ids
import im.gar.titanssh.config.ReconnectBehavior
import im.gar.titanssh.config.ResilienceLevel
import im.gar.titanssh.config.ScriptBehavior
import im.gar.titanssh.config.ScriptFailurePolicy
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.Session
import im.gar.titanssh.config.SessionScript
import im.gar.titanssh.config.Snippet
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.config.Tunnel
import im.gar.titanssh.config.TunnelType
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens

/**
 * Create/edit form for a [Session]: it references a [Host] and adds the guided
 * start-script chain ([[Scripts de inicio por sesión]]), tunnels, working
 * directory, resilience level and appearance, and may override the host's
 * username/port.
 */
@Composable
fun SessionEditor(controller: ConfigController, sessionId: String?, onDone: () -> Unit) {
    val config by controller.state.collectAsState()
    val existing = remember(sessionId) { config.sessions.firstOrNull { it.id == sessionId } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var hostId by remember { mutableStateOf(existing?.hostId) }
    var usernameOverride by remember { mutableStateOf(existing?.usernameOverride ?: "") }
    var portOverride by remember { mutableStateOf(existing?.portOverride?.toString() ?: "") }
    var initialDirectory by remember { mutableStateOf(existing?.initialDirectory ?: "") }
    var resilience by remember { mutableStateOf(existing?.resilienceLevel ?: ResilienceLevel.BASE) }
    var groupId by remember { mutableStateOf(existing?.groupId) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    var marker by remember { mutableStateOf(existing?.marker ?: "[+]") }
    var fontSize by remember { mutableStateOf(existing?.appearance?.fontSize?.toString() ?: "") }

    var scripts by remember { mutableStateOf(existing?.scripts ?: emptyList()) }
    var tunnels by remember { mutableStateOf(existing?.tunnels ?: emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val canSave = name.isNotBlank() && hostId != null

    fun save() {
        val hid = hostId ?: return
        controller.upsertSession(
            Session(
                id = existing?.id ?: Ids.session(),
                name = name.trim(),
                hostId = hid,
                usernameOverride = usernameOverride.ifBlank { null },
                portOverride = portOverride.toIntOrNull(),
                initialDirectory = initialDirectory.ifBlank { null },
                resilienceLevel = resilience,
                scripts = scripts,
                tunnels = tunnels,
                appearance = fontSize.toIntOrNull()?.let { TerminalAppearance(fontSize = it) },
                groupId = groupId,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                marker = marker.ifBlank { "[+]" },
            ),
        )
        onDone()
    }

    EditorScaffold(
        title = if (existing == null) "Nueva sesión" else "Editar sesión",
        onBack = onDone,
        onSave = { save() },
        canSave = canSave,
        onDelete = existing?.let { { controller.deleteSession(it.id); onDone() } },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanTextField("Nombre", name, { name = it }, placeholder = "deploy en prod")
            Gap()
            val hostOptions = config.hosts
            TitanDropdown(
                "Host",
                options = hostOptions,
                selected = hostOptions.firstOrNull { it.id == hostId },
                onSelect = { hostId = it.id },
                optionLabel = { it.alias.ifBlank { it.hostname } },
                placeholder = if (hostOptions.isEmpty()) "crea un host primero" else "elige un host",
            )
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                Box(Modifier.weight(1f)) { TitanTextField("Usuario (override)", usernameOverride, { usernameOverride = it }, placeholder = "hereda del host") }
                Box(Modifier.weight(1f)) { TitanTextField("Puerto (override)", portOverride, { portOverride = it.filter(Char::isDigit) }, placeholder = "hereda") }
            }
            Gap()
            TitanTextField("Directorio inicial (cd)", initialDirectory, { initialDirectory = it }, placeholder = "/srv/miapp")
            Gap()
            TitanSegmented("Nivel de resiliencia", ResilienceLevel.entries, resilience, { resilience = it }, optionLabel = {
                when (it) {
                    ResilienceLevel.BASE -> "base"
                    ResilienceLevel.AUTO_MULTIPLEXER -> "auto-tmux"
                    ResilienceLevel.AGENT -> "agente"
                }
            })

            SectionHeader("Scripts de inicio")
            Caption("Se ejecutan en orden al conectar, según su fase. Reordénalos con [^]/[v].")
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            scripts.forEachIndexed { index, script ->
                ScriptCard(
                    script = script,
                    snippets = config.snippets,
                    expanded = expanded == script.id,
                    isFirst = index == 0,
                    isLast = index == scripts.lastIndex,
                    onToggle = { expanded = if (expanded == script.id) null else script.id },
                    onChange = { updated -> scripts = scripts.map { if (it.id == script.id) updated else it } },
                    onMoveUp = { if (index > 0) scripts = scripts.swap(index, index - 1) },
                    onMoveDown = { if (index < scripts.lastIndex) scripts = scripts.swap(index, index + 1) },
                    onDelete = { scripts = scripts.filterNot { it.id == script.id } },
                )
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir script", onClick = {
                val s = SessionScript(id = Ids.script(), label = "script ${scripts.size + 1}")
                scripts = scripts + s
                expanded = s.id
            }, kind = ButtonKind.SECONDARY)

            SectionHeader("Túneles / port forwarding")
            tunnels.forEach { tunnel ->
                TunnelCard(
                    tunnel = tunnel,
                    expanded = expanded == tunnel.id,
                    onToggle = { expanded = if (expanded == tunnel.id) null else tunnel.id },
                    onChange = { updated -> tunnels = tunnels.map { if (it.id == tunnel.id) updated else it } },
                    onDelete = { tunnels = tunnels.filterNot { it.id == tunnel.id } },
                )
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir túnel", onClick = {
                val t = Tunnel(id = Ids.tunnel(), type = TunnelType.LOCAL, listenPort = 8080)
                tunnels = tunnels + t
                expanded = t.id
            }, kind = ButtonKind.SECONDARY)

            SectionHeader("Organización y apariencia")
            val groupOptions = config.groups
            TitanDropdown(
                "Grupo (proyecto)",
                options = groupOptions,
                selected = groupOptions.firstOrNull { it.id == groupId },
                onSelect = { groupId = if (groupId == it.id) null else it.id },
                optionLabel = { it.name },
                placeholder = "sin grupo",
            )
            Gap()
            TitanTextField("Etiquetas (separadas por coma)", tags, { tags = it }, placeholder = "deploy, europa")
            Gap()
            TitanTextField("Marcador ASCII", marker, { marker = it }, placeholder = "[+]")
            Gap()
            TitanTextField("Tamaño de fuente del terminal (override, opcional)", fontSize, { fontSize = it.filter(Char::isDigit) })
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

@Composable
private fun ScriptCard(
    script: SessionScript,
    snippets: List<Snippet>,
    expanded: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onChange: (SessionScript) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = TitanDimens.SpaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(if (expanded) "[-]" else "[+]", onClick = onToggle, color = TitanColors.Accent)
            Column(Modifier.weight(1f)) {
                Text(script.label.ifBlank { "(sin nombre)" }, style = MaterialTheme.typography.bodyLarge, color = if (script.enabled) TitanColors.Ink else TitanColors.Stone)
                Caption(phaseLabel(script.phase))
            }
            GlyphButton("[^]", onClick = onMoveUp, enabled = !isFirst)
            GlyphButton("[v]", onClick = onMoveDown, enabled = !isLast)
            GlyphButton("[x]", onClick = onDelete, color = TitanColors.Danger)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = TitanDimens.SpaceLg, top = TitanDimens.SpaceSm)) {
                TitanCheck("Habilitado", script.enabled) { onChange(script.copy(enabled = it)) }
                Gap()
                TitanTextField("Nombre / etiqueta", script.label, { onChange(script.copy(label = it)) })
                Gap()
                TitanSegmented("Fase", ScriptPhase.entries, script.phase, { onChange(script.copy(phase = it)) }, optionLabel = { phaseLabel(it) })
                Gap()
                if (snippets.isNotEmpty()) {
                    TitanDropdown(
                        "Insertar snippet (opcional)",
                        options = snippets,
                        selected = snippets.firstOrNull { it.id == script.snippetId },
                        onSelect = { onChange(script.copy(snippetId = it.id, body = it.body)) },
                        optionLabel = { it.name },
                        placeholder = "comando propio",
                    )
                    Gap()
                }
                TitanTextField("Comando(s)  ·  admite \${VAR}", script.body, { onChange(script.copy(body = it, snippetId = null)) }, singleLine = false, placeholder = "cd /srv/app && ./run.sh")
                Gap()
                TitanCheck("Silencioso (no se muestra en el terminal)", script.behavior.silent) { onChange(script.copy(behavior = script.behavior.copy(silent = it))) }
                TitanCheck("Esperar a que termine (secuencial)", script.behavior.waitForCompletion) { onChange(script.copy(behavior = script.behavior.copy(waitForCompletion = it))) }
                Gap()
                TitanSegmented("Si falla", ScriptFailurePolicy.entries, script.behavior.onFailure, { onChange(script.copy(behavior = script.behavior.copy(onFailure = it))) }, optionLabel = { if (it == ScriptFailurePolicy.CONTINUE) "continuar" else "abortar" })
                Gap()
                Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                    Box(Modifier.weight(1f)) { TitanTextField("Retardo (s)", script.behavior.delaySeconds?.toString() ?: "", { onChange(script.copy(behavior = script.behavior.copy(delaySeconds = it.toIntOrNull()))) }) }
                    Box(Modifier.weight(1f)) { TitanTextField("Timeout (s)", script.behavior.timeoutSeconds?.toString() ?: "", { onChange(script.copy(behavior = script.behavior.copy(timeoutSeconds = it.toIntOrNull()))) }) }
                }
                Gap()
                TitanTextField("Esperar patrón antes de enviar (expect)", script.behavior.expectPattern ?: "", { onChange(script.copy(behavior = script.behavior.copy(expectPattern = it.ifBlank { null }))) }, placeholder = "password:")
                Gap()
                TitanTextField("Secretos a inyectar (refs, coma)", script.secretRefs.joinToString(", "), { v -> onChange(script.copy(secretRefs = v.split(",").map { it.trim() }.filter { it.isNotEmpty() })) }, placeholder = "casa.token")
                if (script.phase == ScriptPhase.ON_RECONNECT) {
                    Gap()
                    TitanSegmented("Al reconectar", ReconnectBehavior.entries, script.reconnectBehavior, { onChange(script.copy(reconnectBehavior = it)) }, optionLabel = {
                        when (it) {
                            ReconnectBehavior.RERUN_ALL -> "re-ejecutar"
                            ReconnectBehavior.RESTORE_CD_ONLY -> "solo cd"
                            ReconnectBehavior.NONE -> "nada"
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun TunnelCard(
    tunnel: Tunnel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Tunnel) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = TitanDimens.SpaceXs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(if (expanded) "[-]" else "[+]", onClick = onToggle, color = TitanColors.Accent)
            Column(Modifier.weight(1f)) {
                Text(tunnel.label.ifBlank { tunnelSummary(tunnel) }, style = MaterialTheme.typography.bodyLarge, color = TitanColors.Ink)
                Caption(tunnel.type.name.lowercase())
            }
            GlyphButton("[x]", onClick = onDelete, color = TitanColors.Danger)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = TitanDimens.SpaceLg, top = TitanDimens.SpaceSm)) {
                TitanCheck("Habilitado", tunnel.enabled) { onChange(tunnel.copy(enabled = it)) }
                Gap()
                TitanSegmented("Tipo", TunnelType.entries, tunnel.type, { onChange(tunnel.copy(type = it)) }, optionLabel = {
                    when (it) {
                        TunnelType.LOCAL -> "local"
                        TunnelType.REMOTE -> "remoto"
                        TunnelType.DYNAMIC_SOCKS -> "socks"
                    }
                })
                Gap()
                TitanTextField("Etiqueta", tunnel.label, { onChange(tunnel.copy(label = it)) })
                Gap()
                Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                    Box(Modifier.weight(1f)) { TitanTextField("Escucha (host)", tunnel.listenHost, { onChange(tunnel.copy(listenHost = it)) }) }
                    Box(Modifier.weight(1f)) { TitanTextField("Escucha (puerto)", tunnel.listenPort.toString(), { onChange(tunnel.copy(listenPort = it.toIntOrNull() ?: tunnel.listenPort)) }) }
                }
                if (tunnel.type != TunnelType.DYNAMIC_SOCKS) {
                    Gap()
                    Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                        Box(Modifier.weight(1f)) { TitanTextField("Destino (host)", tunnel.destinationHost ?: "", { onChange(tunnel.copy(destinationHost = it.ifBlank { null })) }) }
                        Box(Modifier.weight(1f)) { TitanTextField("Destino (puerto)", tunnel.destinationPort?.toString() ?: "", { onChange(tunnel.copy(destinationPort = it.toIntOrNull())) }) }
                    }
                }
            }
        }
    }
}

private fun phaseLabel(phase: ScriptPhase): String = when (phase) {
    ScriptPhase.PRE_CONNECT_LOCAL -> "pre-conexión (local)"
    ScriptPhase.ON_SHELL_START -> "al abrir la shell"
    ScriptPhase.POST_INIT -> "post-inicio"
    ScriptPhase.ON_RECONNECT -> "al reconectar"
    ScriptPhase.ON_DEMAND -> "bajo demanda"
}

private fun tunnelSummary(t: Tunnel): String = when (t.type) {
    TunnelType.DYNAMIC_SOCKS -> "socks ${t.listenHost}:${t.listenPort}"
    else -> "${t.listenHost}:${t.listenPort} → ${t.destinationHost ?: "?"}:${t.destinationPort ?: "?"}"
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> =
    toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
