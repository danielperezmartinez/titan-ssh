package im.gar.titanssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.Ids
import im.gar.titanssh.config.ResilienceLevel
import im.gar.titanssh.config.Session
import im.gar.titanssh.config.SessionScript
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.config.Tunnel
import im.gar.titanssh.config.TunnelType
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens

/**
 * The sub-editor currently open on top of the session form. `id == null` means
 * "create a new one"; a non-null id edits that script/tunnel from the in-memory
 * lists. Kept local to the session editor so the (still unsaved) session state is
 * preserved while a script or tunnel is edited on its own screen.
 */
private sealed interface SubEditor {
    data class ScriptEdit(val id: String?) : SubEditor
    data class TunnelEdit(val id: String?) : SubEditor
}

/**
 * Create/edit form for a [Session]: it references a [Host] and adds the guided
 * start-script chain ([[Scripts de inicio por sesión]]), tunnels, working
 * directory, resilience level and appearance, and may override the host's
 * username/port.
 *
 * Scripts and tunnels are no longer edited inline: the form only **lists** them
 * (row + summary) and **navigates** to their own reusable screens ([ScriptEditor]
 * / [TunnelEditor], task [[Editores de script y túnel como pantalla propia]]).
 * They are edited in memory and committed only when the session is saved.
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
    var subEditor by remember { mutableStateOf<SubEditor?>(null) }

    when (val sub = subEditor) {
        is SubEditor.ScriptEdit -> {
            val draft = remember(sub) {
                sub.id?.let { id -> scripts.firstOrNull { it.id == id } }
                    ?: SessionScript(id = Ids.script(), label = "script ${scripts.size + 1}")
            }
            ScriptEditor(
                script = draft,
                snippets = config.snippets,
                isNew = sub.id == null,
                onSave = { updated ->
                    scripts = if (sub.id == null) {
                        scripts + updated
                    } else {
                        scripts.map { if (it.id == updated.id) updated else it }
                    }
                    subEditor = null
                },
                onDelete = {
                    sub.id?.let { id -> scripts = scripts.filterNot { it.id == id } }
                    subEditor = null
                },
                onBack = { subEditor = null },
            )
        }

        is SubEditor.TunnelEdit -> {
            val draft = remember(sub) {
                sub.id?.let { id -> tunnels.firstOrNull { it.id == id } }
                    ?: Tunnel(id = Ids.tunnel(), type = TunnelType.LOCAL, listenPort = 8080)
            }
            TunnelEditor(
                tunnel = draft,
                isNew = sub.id == null,
                onSave = { updated ->
                    tunnels = if (sub.id == null) {
                        tunnels + updated
                    } else {
                        tunnels.map { if (it.id == updated.id) updated else it }
                    }
                    subEditor = null
                },
                onDelete = {
                    sub.id?.let { id -> tunnels = tunnels.filterNot { it.id == id } }
                    subEditor = null
                },
                onBack = { subEditor = null },
            )
        }

        null -> SessionForm(
            controller = controller,
            existing = existing,
            name = name, onName = { name = it },
            hostId = hostId, onHostId = { hostId = it },
            usernameOverride = usernameOverride, onUsernameOverride = { usernameOverride = it },
            portOverride = portOverride, onPortOverride = { portOverride = it },
            initialDirectory = initialDirectory, onInitialDirectory = { initialDirectory = it },
            resilience = resilience, onResilience = { resilience = it },
            groupId = groupId, onGroupId = { groupId = it },
            tags = tags, onTags = { tags = it },
            marker = marker, onMarker = { marker = it },
            fontSize = fontSize, onFontSize = { fontSize = it },
            scripts = scripts, onScripts = { scripts = it },
            tunnels = tunnels,
            onOpenScript = { subEditor = SubEditor.ScriptEdit(it) },
            onOpenTunnel = { subEditor = SubEditor.TunnelEdit(it) },
            onDone = onDone,
        )
    }
}

@Composable
private fun SessionForm(
    controller: ConfigController,
    existing: Session?,
    name: String, onName: (String) -> Unit,
    hostId: String?, onHostId: (String) -> Unit,
    usernameOverride: String, onUsernameOverride: (String) -> Unit,
    portOverride: String, onPortOverride: (String) -> Unit,
    initialDirectory: String, onInitialDirectory: (String) -> Unit,
    resilience: ResilienceLevel, onResilience: (ResilienceLevel) -> Unit,
    groupId: String?, onGroupId: (String?) -> Unit,
    tags: String, onTags: (String) -> Unit,
    marker: String, onMarker: (String) -> Unit,
    fontSize: String, onFontSize: (String) -> Unit,
    scripts: List<SessionScript>, onScripts: (List<SessionScript>) -> Unit,
    tunnels: List<Tunnel>,
    onOpenScript: (String?) -> Unit,
    onOpenTunnel: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val config by controller.state.collectAsState()
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
            TitanTextField("Nombre", name, onName, placeholder = "deploy en prod")
            Gap()
            val hostOptions = config.hosts
            TitanDropdown(
                "Host",
                options = hostOptions,
                selected = hostOptions.firstOrNull { it.id == hostId },
                onSelect = { onHostId(it.id) },
                optionLabel = { it.alias.ifBlank { it.hostname } },
                placeholder = if (hostOptions.isEmpty()) "crea un host primero" else "elige un host",
            )
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                Box(Modifier.weight(1f)) { TitanTextField("Usuario (override)", usernameOverride, onUsernameOverride, placeholder = "hereda del host") }
                Box(Modifier.weight(1f)) { TitanTextField("Puerto (override)", portOverride, { onPortOverride(it.filter(Char::isDigit)) }, placeholder = "hereda") }
            }
            Gap()
            TitanTextField("Directorio inicial (cd)", initialDirectory, onInitialDirectory, placeholder = "/srv/miapp")
            Gap()
            TitanSegmented("Nivel de resiliencia", ResilienceLevel.entries, resilience, onResilience, optionLabel = {
                when (it) {
                    ResilienceLevel.BASE -> "base"
                    ResilienceLevel.AUTO_MULTIPLEXER -> "auto-tmux"
                    ResilienceLevel.AGENT -> "agente"
                }
            })

            SectionHeader("Scripts de inicio")
            Caption("Se ejecutan en orden al conectar, según su fase. Reordénalos con [^]/[v].")
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            if (scripts.isEmpty()) {
                EmptyState("Sin scripts. Añade el primero con [+].")
            }
            scripts.forEachIndexed { index, script ->
                ListRow(
                    marker = if (script.enabled) "[>]" else "[ ]",
                    title = script.label.ifBlank { "(sin nombre)" },
                    subtitle = phaseLabel(script.phase) + if (script.enabled) "" else "  ·  deshabilitado",
                    onClick = { onOpenScript(script.id) },
                    markerColor = if (script.enabled) TitanColors.Body else TitanColors.Stone,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs)) {
                            GlyphButton("[^]", onClick = { if (index > 0) onScripts(scripts.swap(index, index - 1)) }, enabled = index > 0)
                            GlyphButton("[v]", onClick = { if (index < scripts.lastIndex) onScripts(scripts.swap(index, index + 1)) }, enabled = index < scripts.lastIndex)
                        }
                    },
                )
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir script", onClick = { onOpenScript(null) }, kind = ButtonKind.SECONDARY)

            SectionHeader("Túneles / port forwarding")
            if (tunnels.isEmpty()) {
                EmptyState("Sin túneles.")
            }
            tunnels.forEach { tunnel ->
                ListRow(
                    marker = if (tunnel.enabled) "[>]" else "[ ]",
                    title = tunnel.label.ifBlank { tunnelSummary(tunnel) },
                    subtitle = tunnelSummary(tunnel) + if (tunnel.enabled) "" else "  ·  deshabilitado",
                    onClick = { onOpenTunnel(tunnel.id) },
                    markerColor = if (tunnel.enabled) TitanColors.Body else TitanColors.Stone,
                )
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir túnel", onClick = { onOpenTunnel(null) }, kind = ButtonKind.SECONDARY)

            SectionHeader("Organización y apariencia")
            val groupOptions = config.groups
            TitanDropdown(
                "Grupo (proyecto)",
                options = groupOptions,
                selected = groupOptions.firstOrNull { it.id == groupId },
                onSelect = { onGroupId(if (groupId == it.id) null else it.id) },
                optionLabel = { it.name },
                placeholder = "sin grupo",
            )
            Gap()
            TitanTextField("Etiquetas (separadas por coma)", tags, onTags, placeholder = "deploy, europa")
            Gap()
            TitanTextField("Marcador ASCII", marker, onMarker, placeholder = "[+]")
            Gap()
            TitanTextField("Tamaño de fuente del terminal (override, opcional)", fontSize, { onFontSize(it.filter(Char::isDigit)) })
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> =
    toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
