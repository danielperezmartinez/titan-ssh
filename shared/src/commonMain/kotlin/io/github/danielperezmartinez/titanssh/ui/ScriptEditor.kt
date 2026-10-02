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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.danielperezmartinez.titanssh.config.Ids
import io.github.danielperezmartinez.titanssh.config.LibraryScript
import io.github.danielperezmartinez.titanssh.config.ReconnectBehavior
import io.github.danielperezmartinez.titanssh.config.ScriptBehavior
import io.github.danielperezmartinez.titanssh.config.ScriptFailurePolicy
import io.github.danielperezmartinez.titanssh.config.ScriptPhase
import io.github.danielperezmartinez.titanssh.config.SessionScript
import io.github.danielperezmartinez.titanssh.config.filledFrom
import io.github.danielperezmartinez.titanssh.config.reference
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * Full-screen create/edit form for a single [SessionScript], reachable from the
 * session editor's script list (task [[Editores de script y túnel como pantalla
 * propia]]).
 *
 * The script is either the session's own, with every v1 attribute editable
 * (command body, behavior, environment variables and injected secret refs), or
 * a reference to a [LibraryScript] (ADR-0013). A reference only edits how the
 * session uses it (enabled, phase, reconnect behavior) and shows the library's
 * command read-only; it can be turned into an own copy. An own script can be
 * saved to the library, which links it.
 *
 * The script is edited in memory on a local [draft]: the session it belongs to is
 * not persisted yet, so [onSave] just hands the updated value back to the session
 * editor, which commits everything when the session itself is saved. Only
 * [onSaveToLibrary] writes at once, since the library does not belong to the
 * session. [onDelete] removes it from the session's list (only meaningful when
 * editing an existing one; a library script stays in the library); [onBack]
 * discards the changes.
 */
@Composable
fun ScriptEditor(
    script: SessionScript,
    library: List<LibraryScript>,
    isNew: Boolean,
    onSave: (SessionScript) -> Unit,
    onSaveToLibrary: (LibraryScript) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(script.id) { mutableStateOf(script) }
    // envVars is edited as free text ("KEY=value" per line) and parsed on save.
    var envText by remember(script.id) { mutableStateOf(formatEnv(script.envVars)) }
    val linked = draft.libraryScriptId?.let { id -> library.firstOrNull { it.id == id } }

    // A reference keeps only how the session uses the script; its content lives
    // in the library, so the own body/behavior are dropped rather than kept stale.
    fun linkTo(entry: LibraryScript) {
        draft = entry.reference(draft.id, draft.phase)
            .copy(enabled = draft.enabled, reconnectBehavior = draft.reconnectBehavior)
    }

    fun finished(): SessionScript =
        if (draft.libraryScriptId != null) draft else draft.copy(envVars = parseEnv(envText))

    EditorScaffold(
        title = if (isNew) "Nuevo script" else "Editar script",
        onBack = onBack,
        onSave = { onSave(finished()) },
        canSave = true,
        onDelete = if (isNew) null else onDelete,
        // A library reference only leaves this session; an own script is gone.
        deleteLabel = if (draft.libraryScriptId != null) "[x] Quitar" else "[x] Eliminar",
        // Same wording as the [x] of the script's row in the session form.
        deleteQuestion = if (draft.libraryScriptId != null) "¿Quitar de la sesión?" else "¿Eliminar el script?",
        deleteSubtitle = if (linked != null) "Sigue en la biblioteca" else null,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanCheck("Habilitado", draft.enabled) { draft = draft.copy(enabled = it) }
            Gap()
            TitanSegmented("Fase", ScriptPhase.entries, draft.phase, { draft = draft.copy(phase = it) }, optionLabel = { phaseLabel(it) })
            if (draft.phase == ScriptPhase.ON_RECONNECT) {
                Gap()
                TitanSegmented("Al reconectar", ReconnectBehavior.entries, draft.reconnectBehavior, { draft = draft.copy(reconnectBehavior = it) }, optionLabel = {
                    when (it) {
                        ReconnectBehavior.RERUN_ALL -> "re-ejecutar"
                        ReconnectBehavior.RESTORE_CD_ONLY -> "solo cd"
                        ReconnectBehavior.NONE -> "nada"
                    }
                })
            }

            SectionHeader("Origen")
            if (library.isNotEmpty()) {
                TitanDropdown(
                    "Script de la biblioteca",
                    options = library,
                    selected = linked,
                    onSelect = { linkTo(it) },
                    optionLabel = { it.name },
                    placeholder = "ninguno: script propio",
                )
                Gap()
            }
            when {
                linked != null -> {
                    Caption("Se edita en Configuración → Scripts. Los cambios llegan a todas las sesiones que lo usan.")
                    Gap()
                    TitanButton("[~] Hacer copia propia", onClick = {
                        draft = draft.filledFrom(linked).copy(libraryScriptId = null)
                        envText = formatEnv(linked.envVars)
                    })
                }
                draft.libraryScriptId != null -> {
                    Caption("El script de la biblioteca ya no existe: no se ejecutará.", color = TitanColors.Danger)
                    Gap()
                    TitanButton("[x] Quitar el enlace", onClick = { draft = draft.copy(libraryScriptId = null) })
                }
                else -> {
                    Caption("Script propio de esta sesión.")
                    Gap()
                    TitanButton("[+] Guardar en la biblioteca", onClick = {
                        val own = finished()
                        val entry = LibraryScript(
                            id = Ids.libraryScript(),
                            name = own.label.ifBlank { "script" },
                            body = own.body,
                            behavior = own.behavior,
                            envVars = own.envVars,
                            secretRefs = own.secretRefs,
                        )
                        onSaveToLibrary(entry)
                        linkTo(entry)
                    })
                }
            }

            if (linked != null) {
                SectionHeader("Comando (de la biblioteca)")
                TitanTextField("Comando(s)", linked.body, {}, singleLine = false, enabled = false)
            } else if (draft.libraryScriptId == null) {
                SectionHeader("Comando")
                TitanTextField("Nombre / etiqueta", draft.label, { draft = draft.copy(label = it) }, placeholder = "arrancar servicio")
                Gap()
                TitanTextField(
                    "Comando(s)  ·  admite \${VAR}",
                    draft.body,
                    { draft = draft.copy(body = it) },
                    singleLine = false,
                    placeholder = "cd /srv/app && ./run.sh",
                )
                ScriptBehaviorFields(draft.behavior) { draft = draft.copy(behavior = it) }
                ScriptDataFields(
                    envText = envText,
                    onEnvText = { envText = it },
                    secretRefs = draft.secretRefs,
                    onSecretRefs = { draft = draft.copy(secretRefs = it) },
                )
            }
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

/** The "Comportamiento" section, shared by the session and library script editors. */
@Composable
internal fun ScriptBehaviorFields(behavior: ScriptBehavior, onChange: (ScriptBehavior) -> Unit) {
    SectionHeader("Comportamiento")
    TitanCheck("Silencioso (no se muestra en el terminal)", behavior.silent) { onChange(behavior.copy(silent = it)) }
    TitanCheck("Esperar a que termine (secuencial)", behavior.waitForCompletion) { onChange(behavior.copy(waitForCompletion = it)) }
    // The wait is a line typed in the destination's shell syntax, which a
    // shell started by the script (pwsh from cmd.exe) would not run.
    Caption("Un script que abre otra shell (pwsh, bash…) va el último: la espera de los siguientes usa la sintaxis de la shell de antes.")
    Gap()
    TitanSegmented("Si falla", ScriptFailurePolicy.entries, behavior.onFailure, { onChange(behavior.copy(onFailure = it)) }, optionLabel = { if (it == ScriptFailurePolicy.CONTINUE) "continuar" else "abortar" })
    Gap()
    Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
        Box(Modifier.weight(1f)) { TitanTextField("Retardo (s)", behavior.delaySeconds?.toString() ?: "", { v -> onChange(behavior.copy(delaySeconds = v.filter(Char::isDigit).toIntOrNull())) }) }
        Box(Modifier.weight(1f)) { TitanTextField("Timeout (s)", behavior.timeoutSeconds?.toString() ?: "", { v -> onChange(behavior.copy(timeoutSeconds = v.filter(Char::isDigit).toIntOrNull())) }) }
    }
    Gap()
    TitanTextField("Esperar patrón antes de enviar (expect)", behavior.expectPattern ?: "", { onChange(behavior.copy(expectPattern = it.ifBlank { null })) }, placeholder = "password:")
}

/** The "Datos y seguridad" section, shared by the session and library script editors. */
@Composable
internal fun ScriptDataFields(
    envText: String,
    onEnvText: (String) -> Unit,
    secretRefs: List<String>,
    onSecretRefs: (List<String>) -> Unit,
) {
    SectionHeader("Datos y seguridad")
    TitanTextField("Variables de entorno (KEY=valor por línea)", envText, onEnvText, singleLine = false, placeholder = "NODE_ENV=production")
    Gap()
    TitanTextField("Secretos a inyectar (refs, coma)", secretRefs.joinToString(", "), { v -> onSecretRefs(v.split(",").map { it.trim() }.filter { it.isNotEmpty() }) }, placeholder = "casa.token")
    if (secretRefs.isNotEmpty()) {
        Spacer(Modifier.height(TitanDimens.SpaceXs))
        Caption(
            "Un script con secretos se envía sin eco: no se ve en la terminal ni queda en el historial del shell. " +
                "En cmd.exe no se ejecuta; usa PowerShell como shell del destino.",
        )
    }
}

/** Renders a [ScriptPhase] as the Spanish label shared across the session UI. */
internal fun phaseLabel(phase: ScriptPhase): String = when (phase) {
    ScriptPhase.PRE_CONNECT_LOCAL -> "pre-conexión (local)"
    ScriptPhase.ON_SHELL_START -> "al abrir la shell"
    ScriptPhase.POST_INIT -> "post-inicio"
    ScriptPhase.ON_RECONNECT -> "al reconectar"
    ScriptPhase.ON_DEMAND -> "bajo demanda"
}

/** Parses a "KEY=value" per-line block into an ordered env-var map. */
internal fun parseEnv(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf('=')
        if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
    }.toMap()

/** Formats an env-var map back into the "KEY=value" per-line block. */
internal fun formatEnv(env: Map<String, String>): String =
    env.entries.joinToString("\n") { "${it.key}=${it.value}" }

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
