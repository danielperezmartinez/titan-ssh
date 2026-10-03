package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Column
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
import io.github.danielperezmartinez.titanssh.config.Ids
import io.github.danielperezmartinez.titanssh.config.LibraryScript
import io.github.danielperezmartinez.titanssh.config.ScriptBehavior
import io.github.danielperezmartinez.titanssh.config.sessionsUsing
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * Create/edit form for a [LibraryScript] of the "Scripts" tab (ADR-0013): the
 * command and how it runs, reused by reference from any session and runnable
 * on demand from an open one. Deleting asks first (and says how many sessions
 * use it); each of them keeps an own copy ([ConfigController.deleteLibraryScript]).
 */
@Composable
fun LibraryScriptEditor(controller: ConfigController, libraryScriptId: String?, onDone: () -> Unit) {
    val config by controller.state.collectAsState()
    val existing = remember(libraryScriptId) { config.scripts.firstOrNull { it.id == libraryScriptId } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    var behavior by remember { mutableStateOf(existing?.behavior ?: ScriptBehavior()) }
    var envText by remember { mutableStateOf(formatEnv(existing?.envVars ?: emptyMap())) }
    var secretRefs by remember { mutableStateOf(existing?.secretRefs ?: emptyList()) }

    val usedBy = existing?.let { config.sessionsUsing(it.id) } ?: emptyList()
    val canSave = name.isNotBlank()

    fun save() {
        controller.upsertLibraryScript(
            LibraryScript(
                id = existing?.id ?: Ids.libraryScript(),
                name = name.trim(),
                body = body,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                behavior = behavior,
                envVars = parseEnv(envText),
                secretRefs = secretRefs,
            ),
        )
        onDone()
    }

    fun delete() {
        existing?.let { controller.deleteLibraryScript(it.id) }
        onDone()
    }

    EditorScaffold(
        title = if (existing == null) "Nuevo script" else "Editar script",
        onBack = onDone,
        onSave = { save() },
        canSave = canSave,
        onDelete = existing?.let { { delete() } },
        deleteQuestion = "¿Eliminar el script?",
        deleteSubtitle = libraryScriptDeleteWarning(usedBy.size),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            if (usedBy.isNotEmpty()) {
                Caption("Lo usan ${usedBy.size} sesión(es). Los cambios llegan a todas.")
                Spacer(Modifier.height(TitanDimens.SpaceMd))
            }
            TitanTextField("Nombre", name, { name = it }, placeholder = "reiniciar servicio")
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            TitanTextField("Comando(s)  ·  admite \${VAR}", body, { body = it }, placeholder = "sudo systemctl restart myapp", singleLine = false)
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            TitanTextField("Etiquetas (separadas por coma)", tags, { tags = it }, placeholder = "ops, deploy")
            ScriptBehaviorFields(behavior) { behavior = it }
            ScriptDataFields(
                envText = envText,
                onEnvText = { envText = it },
                secretRefs = secretRefs,
                onSecretRefs = { secretRefs = it },
            )
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

/**
 * What deleting a library script used by [uses] sessions means, for its delete
 * confirmation; `null` when no session uses it.
 */
internal fun libraryScriptDeleteWarning(uses: Int): String? = when (uses) {
    0 -> null
    1 -> "Lo usa 1 sesión: se queda con una copia propia"
    else -> "Lo usan $uses sesiones: cada una se queda con una copia propia"
}
