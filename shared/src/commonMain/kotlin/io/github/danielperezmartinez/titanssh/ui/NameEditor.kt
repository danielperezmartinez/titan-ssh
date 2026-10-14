package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch

/** The agent keeps up to this many characters of a name (`MaxNameRunes` in the agent). */
internal const val MAX_INSTANCE_NAME = 64

/**
 * Edits the name the user gives a session instance ([[Renombrar las pestañas
 * y las sesiones activas]]): a field that opens focused on [initial], with
 * [placeholder] (the name a blank one goes back to), `[ok]` and `[x]`.
 * [onSave] applies the name and returns why it could not be kept, or null,
 * which ends the edit through [onDone]; meanwhile the form waits. [hint] goes
 * under it until an error replaces it.
 */
@Composable
internal fun NameEditor(
    key: Any,
    initial: String,
    placeholder: String,
    hint: String,
    onSave: suspend (String) -> String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(key) { mutableStateOf(initial) }
    var saving by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember(key) { FocusRequester() }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }
    val save = {
        if (!saving) {
            saving = true
            error = null
            scope.launch {
                val why = onSave(name)
                saving = false
                if (why == null) onDone() else error = why
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitanTextField(
                label = "",
                value = name,
                onValueChange = { name = it.take(MAX_INSTANCE_NAME) },
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                enabled = !saving,
                focusRequester = focus,
                onDone = { save() },
            )
            Spacer(Modifier.width(TitanDimens.SpaceSm))
            TitanButton(if (saving) "[-]" else "[ok]", onClick = { save() }, kind = ButtonKind.PRIMARY, enabled = !saving)
            Spacer(Modifier.width(TitanDimens.SpaceXs))
            TitanButton("[x]", onClick = onDone, enabled = !saving)
        }
        Caption(
            error ?: hint,
            Modifier.padding(top = TitanDimens.SpaceXs),
            color = if (error != null) TitanColors.Danger else TitanColors.Mute,
        )
    }
}

/** What [NameEditor] says about where a name is kept: in the agent ([inAgent]) or only in the tab. */
internal fun nameHint(inAgent: Boolean): String =
    if (inAgent) {
        "Se guarda en el agente del destino: lo verán tus otros dispositivos. Vacío: vuelve al nombre de la sesión."
    } else {
        "Solo en esta pestaña. Vacío: vuelve al nombre de la sesión."
    }
