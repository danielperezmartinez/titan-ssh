package io.github.danielperezmartinez.titanssh.ui

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
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.GroupScope
import io.github.danielperezmartinez.titanssh.config.Ids
import io.github.danielperezmartinez.titanssh.config.MousepadSettings
import io.github.danielperezmartinez.titanssh.config.ResilienceLevel
import io.github.danielperezmartinez.titanssh.config.Session
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.SessionType
import io.github.danielperezmartinez.titanssh.terminal.MousepadMotion
import io.github.danielperezmartinez.titanssh.config.TerminalAppearance
import io.github.danielperezmartinez.titanssh.config.Tunnel
import io.github.danielperezmartinez.titanssh.config.TunnelType
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

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
fun SessionEditor(
    controller: ConfigController,
    sessionId: String?,
    onDone: () -> Unit,
    /** Deletes the session; the app also terminates it on its destination (level 3). */
    deleteSession: (Session) -> Unit = { controller.deleteSession(it.id) },
    /** Whether the session is still alive on its destination, so deleting it also terminates it. */
    isLiveOnDestination: (Session) -> Boolean = { false },
    /** The group a new session starts in (created from a folder's actions). */
    initialGroupId: String? = null,
) {
    val config by controller.state.collectAsState()
    val existing = remember(sessionId) { config.sessions.firstOrNull { it.id == sessionId } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var hostId by remember { mutableStateOf(existing?.hostId) }
    var usernameOverride by remember { mutableStateOf(existing?.usernameOverride ?: "") }
    var portOverride by remember { mutableStateOf(existing?.portOverride?.toString() ?: "") }
    var initialDirectory by remember { mutableStateOf(existing?.initialDirectory ?: "") }
    var resilience by remember { mutableStateOf(existing?.resilienceLevel ?: ResilienceLevel.BASE) }
    var groupId by remember { mutableStateOf(if (existing != null) existing.groupId else initialGroupId) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    var marker by remember { mutableStateOf(existing?.marker ?: "[+]") }
    var fontSize by remember { mutableStateOf(existing?.appearance?.fontSize?.toString() ?: "") }

    var type by remember { mutableStateOf(existing?.type ?: SessionType.TERMINAL) }
    var mousepad by remember { mutableStateOf(existing?.mousepad ?: MousepadSettings()) }
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
                library = config.scripts,
                isNew = sub.id == null,
                onSave = { updated ->
                    scripts = if (sub.id == null) {
                        scripts + updated
                    } else {
                        scripts.map { if (it.id == updated.id) updated else it }
                    }
                    subEditor = null
                },
                onSaveToLibrary = { controller.upsertLibraryScript(it) },
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
            deleteSession = deleteSession,
            isLiveOnDestination = isLiveOnDestination,
            name = name, onName = { name = it },
            type = type, onType = { type = it },
            mousepad = mousepad, onMousepad = { mousepad = it },
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
            tunnels = tunnels, onTunnels = { tunnels = it },
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
    deleteSession: (Session) -> Unit,
    isLiveOnDestination: (Session) -> Boolean,
    name: String, onName: (String) -> Unit,
    type: SessionType, onType: (SessionType) -> Unit,
    mousepad: MousepadSettings, onMousepad: (MousepadSettings) -> Unit,
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
    tunnels: List<Tunnel>, onTunnels: (List<Tunnel>) -> Unit,
    onOpenScript: (String?) -> Unit,
    onOpenTunnel: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val config by controller.state.collectAsState()
    val canSave = name.isNotBlank() && hostId != null
    // The script or tunnel whose row is asking to confirm its removal; one at a time.
    var confirmingRemoval by remember { mutableStateOf<String?>(null) }

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
                colorHex = existing?.colorHex,
                type = type,
                mousepad = mousepad,
            ),
        )
        onDone()
    }

    // Same wording as the launcher's "Eliminar": deleting a live session also
    // terminates it on its destination.
    val live = existing?.let(isLiveOnDestination) ?: false
    EditorScaffold(
        title = if (existing == null) "Nueva sesión" else "Editar sesión",
        onBack = onDone,
        onSave = { save() },
        canSave = canSave,
        onDelete = existing?.let { { deleteSession(it); onDone() } },
        deleteQuestion = "¿Eliminar la sesión?",
        deleteSubtitle = if (live) "Sigue viva en el destino" else null,
        deleteConfirmLabel = if (live) "[x] Eliminar y terminarla" else "[x] Sí",
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanTextField("Nombre", name, onName, placeholder = "deploy en prod")
            Gap()
            TitanSegmented("Tipo", SessionType.entries, type, onType, optionLabel = {
                when (it) {
                    SessionType.TERMINAL -> "terminal"
                    SessionType.MOUSEPAD -> "mouse pad"
                }
            })
            if (type == SessionType.MOUSEPAD) {
                Spacer(Modifier.height(TitanDimens.SpaceXs))
                Caption(
                    "El móvil hace de touchpad y teclado del escritorio del destino. Usa el agente, " +
                        "que se instala solo; por ahora, solo en destinos Windows con la sesión iniciada.",
                )
            }
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
            if (type == SessionType.MOUSEPAD) {
                MousepadFields(mousepad, onMousepad)
                SectionHeader("Organización")
                GroupPicker(controller, GroupScope.SESSIONS, config.sessionGroups, groupId, onGroupId)
                Gap()
                TitanTextField("Etiquetas (separadas por coma)", tags, onTags, placeholder = "casa, oficina")
                Gap()
                TitanTextField("Marcador ASCII", marker, onMarker, placeholder = "[+]")
                Spacer(Modifier.height(TitanDimens.SpaceSection))
                return@Column
            }
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
            Caption("Se ejecutan en orden al conectar, según su fase. Reordénalos con [^]/[v]. Los de fase bajo demanda se lanzan desde la pestaña.")
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            if (scripts.isEmpty()) {
                EmptyState("Sin scripts. Añade el primero con [+].")
            }
            scripts.forEachIndexed { index, script ->
                val library = script.libraryScriptId?.let { id -> config.scripts.firstOrNull { it.id == id } }
                val origin = when {
                    library != null -> "  ·  biblioteca"
                    script.libraryScriptId != null -> "  ·  falta en la biblioteca"
                    else -> ""
                }
                if (confirmingRemoval == script.id) {
                    // A library reference only leaves this session; an own
                    // script exists nowhere else, so removing it deletes it.
                    ConfirmRow(
                        question = if (script.libraryScriptId != null) "¿Quitar de la sesión?" else "¿Eliminar el script?",
                        subtitle = if (library != null) "Sigue en la biblioteca" else null,
                        confirmLabel = "[x] Sí",
                        marker = "[x]",
                        markerColor = TitanColors.Danger,
                        onConfirm = {
                            onScripts(scripts.filterNot { it.id == script.id })
                            confirmingRemoval = null
                        },
                        onCancel = { confirmingRemoval = null },
                    )
                } else {
                    ListRow(
                        marker = if (script.enabled) "[>]" else "[ ]",
                        title = (library?.name ?: script.label).ifBlank { "(sin nombre)" },
                        subtitle = phaseLabel(script.phase) + origin + if (script.enabled) "" else "  ·  deshabilitado",
                        onClick = { onOpenScript(script.id) },
                        markerColor = if (script.enabled) TitanColors.Body else TitanColors.Stone,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceXs)) {
                                GlyphButton("[^]", onClick = { if (index > 0) onScripts(scripts.swap(index, index - 1)) }, enabled = index > 0)
                                GlyphButton("[v]", onClick = { if (index < scripts.lastIndex) onScripts(scripts.swap(index, index + 1)) }, enabled = index < scripts.lastIndex)
                                GlyphButton("[x]", onClick = { confirmingRemoval = script.id }, color = TitanColors.Danger)
                            }
                        },
                    )
                }
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir script", onClick = { onOpenScript(null) }, kind = ButtonKind.SECONDARY)

            SectionHeader("Túneles / port forwarding")
            if (tunnels.isEmpty()) {
                EmptyState("Sin túneles.")
            }
            tunnels.forEach { tunnel ->
                if (confirmingRemoval == tunnel.id) {
                    ConfirmRow(
                        question = "¿Eliminar el túnel?",
                        confirmLabel = "[x] Sí",
                        marker = "[x]",
                        markerColor = TitanColors.Danger,
                        onConfirm = {
                            onTunnels(tunnels.filterNot { it.id == tunnel.id })
                            confirmingRemoval = null
                        },
                        onCancel = { confirmingRemoval = null },
                    )
                } else {
                    ListRow(
                        marker = if (tunnel.enabled) "[>]" else "[ ]",
                        title = tunnel.label.ifBlank { tunnelSummary(tunnel) },
                        subtitle = listOfNotNull(
                            tunnelSummary(tunnel),
                            tunnelExposure(tunnel),
                            "deshabilitado".takeUnless { tunnel.enabled },
                        ).joinToString("  ·  "),
                        onClick = { onOpenTunnel(tunnel.id) },
                        markerColor = if (tunnel.enabled) TitanColors.Body else TitanColors.Stone,
                        trailing = {
                            GlyphButton("[x]", onClick = { confirmingRemoval = tunnel.id }, color = TitanColors.Danger)
                        },
                    )
                }
                Hairline()
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[+] Añadir túnel", onClick = { onOpenTunnel(null) }, kind = ButtonKind.SECONDARY)

            SectionHeader("Organización y apariencia")
            GroupPicker(controller, GroupScope.SESSIONS, config.sessionGroups, groupId, onGroupId)
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

/** A mouse pad session's own settings: pointer speed and scroll direction (ADR-0016). */
@Composable
private fun MousepadFields(settings: MousepadSettings, onChange: (MousepadSettings) -> Unit) {
    SectionHeader("Mouse pad")
    TitanSegmented(
        "Velocidad del puntero",
        MousepadMotion.SPEED_PRESETS.map { it.second },
        MousepadMotion.nearestPreset(settings.pointerSpeed),
        { onChange(settings.copy(pointerSpeed = it)) },
        optionLabel = { speed -> MousepadMotion.SPEED_PRESETS.first { it.second == speed }.first.lowercase() },
    )
    Gap()
    TitanCheck("Scroll natural: el contenido sigue a los dedos", settings.naturalScroll) {
        onChange(settings.copy(naturalScroll = it))
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> =
    toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
