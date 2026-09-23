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
import io.github.danielperezmartinez.titanssh.config.Snippet
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * Create/edit form for a library [Snippet] — a reusable command insertable into
 * any session and the source of the "on demand" scripts.
 */
@Composable
fun SnippetEditor(controller: ConfigController, snippetId: String?, onDone: () -> Unit) {
    val config by controller.state.collectAsState()
    val existing = remember(snippetId) { config.snippets.firstOrNull { it.id == snippetId } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }

    val canSave = name.isNotBlank()

    fun save() {
        controller.upsertSnippet(
            Snippet(
                id = existing?.id ?: Ids.snippet(),
                name = name.trim(),
                body = body,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            ),
        )
        onDone()
    }

    EditorScaffold(
        title = if (existing == null) "Nuevo snippet" else "Editar snippet",
        onBack = onDone,
        onSave = { save() },
        canSave = canSave,
        onDelete = existing?.let { { controller.deleteSnippet(it.id); onDone() } },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanTextField("Nombre", name, { name = it }, placeholder = "reiniciar servicio")
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            TitanTextField("Comando(s)", body, { body = it }, placeholder = "sudo systemctl restart myapp", singleLine = false)
            Spacer(Modifier.height(TitanDimens.SpaceMd))
            TitanTextField("Etiquetas (separadas por coma)", tags, { tags = it }, placeholder = "ops, deploy")
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}
