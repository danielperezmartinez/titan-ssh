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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import im.gar.titanssh.config.ReconnectBehavior
import im.gar.titanssh.config.ScriptFailurePolicy
import im.gar.titanssh.config.ScriptPhase
import im.gar.titanssh.config.SessionScript
import im.gar.titanssh.config.Snippet
import im.gar.titanssh.theme.TitanDimens

/**
 * Full-screen create/edit form for a single [SessionScript], reachable from the
 * session editor's script list (task [[Editores de script y túnel como pantalla
 * propia]]). It replaces the former inline expandable card and exposes every v1
 * attribute: phase, snippet insertion, command body, behavior (silent, wait,
 * timeout, on-failure, delay, expect), reconnect behavior, environment variables
 * and injected secret refs.
 *
 * The script is edited in memory on a local [draft]: the session it belongs to is
 * not persisted yet, so [onSave] just hands the updated value back to the session
 * editor, which commits everything when the session itself is saved. [onDelete]
 * removes it from the session's list (only meaningful when editing an existing
 * one); [onBack] discards the changes.
 */
@Composable
fun ScriptEditor(
    script: SessionScript,
    snippets: List<Snippet>,
    isNew: Boolean,
    onSave: (SessionScript) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(script.id) { mutableStateOf(script) }
    // envVars is edited as free text ("KEY=value" per line) and parsed on save.
    var envText by remember(script.id) { mutableStateOf(formatEnv(script.envVars)) }

    EditorScaffold(
        title = if (isNew) "Nuevo script" else "Editar script",
        onBack = onBack,
        onSave = { onSave(draft.copy(envVars = parseEnv(envText))) },
        canSave = true,
        onDelete = if (isNew) null else onDelete,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanCheck("Habilitado", draft.enabled) { draft = draft.copy(enabled = it) }
            Gap()
            TitanTextField("Nombre / etiqueta", draft.label, { draft = draft.copy(label = it) }, placeholder = "arrancar servicio")
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

            SectionHeader("Comando")
            if (snippets.isNotEmpty()) {
                TitanDropdown(
                    "Insertar snippet (opcional)",
                    options = snippets,
                    selected = snippets.firstOrNull { it.id == draft.snippetId },
                    onSelect = { draft = draft.copy(snippetId = it.id, body = it.body) },
                    optionLabel = { it.name },
                    placeholder = "comando propio",
                )
                Gap()
            }
            TitanTextField(
                "Comando(s)  ·  admite \${VAR}",
                draft.body,
                { draft = draft.copy(body = it, snippetId = null) },
                singleLine = false,
                placeholder = "cd /srv/app && ./run.sh",
            )

            SectionHeader("Comportamiento")
            TitanCheck("Silencioso (no se muestra en el terminal)", draft.behavior.silent) { draft = draft.copy(behavior = draft.behavior.copy(silent = it)) }
            TitanCheck("Esperar a que termine (secuencial)", draft.behavior.waitForCompletion) { draft = draft.copy(behavior = draft.behavior.copy(waitForCompletion = it)) }
            Gap()
            TitanSegmented("Si falla", ScriptFailurePolicy.entries, draft.behavior.onFailure, { draft = draft.copy(behavior = draft.behavior.copy(onFailure = it)) }, optionLabel = { if (it == ScriptFailurePolicy.CONTINUE) "continuar" else "abortar" })
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                Box(Modifier.weight(1f)) { TitanTextField("Retardo (s)", draft.behavior.delaySeconds?.toString() ?: "", { v -> draft = draft.copy(behavior = draft.behavior.copy(delaySeconds = v.filter(Char::isDigit).toIntOrNull())) }) }
                Box(Modifier.weight(1f)) { TitanTextField("Timeout (s)", draft.behavior.timeoutSeconds?.toString() ?: "", { v -> draft = draft.copy(behavior = draft.behavior.copy(timeoutSeconds = v.filter(Char::isDigit).toIntOrNull())) }) }
            }
            Gap()
            TitanTextField("Esperar patrón antes de enviar (expect)", draft.behavior.expectPattern ?: "", { draft = draft.copy(behavior = draft.behavior.copy(expectPattern = it.ifBlank { null })) }, placeholder = "password:")

            SectionHeader("Datos y seguridad")
            TitanTextField("Variables de entorno (KEY=valor por línea)", envText, { envText = it }, singleLine = false, placeholder = "NODE_ENV=production")
            Gap()
            TitanTextField("Secretos a inyectar (refs, coma)", draft.secretRefs.joinToString(", "), { v -> draft = draft.copy(secretRefs = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }) }, placeholder = "casa.token")
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
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
private fun parseEnv(text: String): Map<String, String> =
    text.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val idx = trimmed.indexOf('=')
        if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
    }.toMap()

/** Formats an env-var map back into the "KEY=value" per-line block. */
private fun formatEnv(env: Map<String, String>): String =
    env.entries.joinToString("\n") { "${it.key}=${it.value}" }

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
