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
import io.github.danielperezmartinez.titanssh.config.Tunnel
import io.github.danielperezmartinez.titanssh.config.TunnelType
import io.github.danielperezmartinez.titanssh.theme.TitanDimens

/**
 * Full-screen create/edit form for a single [Tunnel] (port forwarding), reachable
 * from the session editor's tunnel list (task [[Editores de script y túnel como
 * pantalla propia]]). It replaces the former inline expandable card and exposes
 * every v1 attribute: type, label, enabled, listen host/port and — for
 * LOCAL/REMOTE forwards — the destination host/port.
 *
 * Edited in memory on a local [draft]; [onSave] hands the updated value back to
 * the session editor (which commits on session save), [onDelete] removes it from
 * the session's list, [onBack] discards the changes.
 */
@Composable
fun TunnelEditor(
    tunnel: Tunnel,
    isNew: Boolean,
    onSave: (Tunnel) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(tunnel.id) { mutableStateOf(tunnel) }

    val canSave = draft.listenPort in 1..65535 &&
        (draft.type == TunnelType.DYNAMIC_SOCKS || (draft.destinationPort?.let { it in 1..65535 } ?: false))

    EditorScaffold(
        title = if (isNew) "Nuevo túnel" else "Editar túnel",
        onBack = onBack,
        onSave = { onSave(draft) },
        canSave = canSave,
        onDelete = if (isNew) null else onDelete,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanCheck("Habilitado", draft.enabled) { draft = draft.copy(enabled = it) }
            Gap()
            TitanSegmented("Tipo", TunnelType.entries, draft.type, { draft = draft.copy(type = it) }, optionLabel = {
                when (it) {
                    TunnelType.LOCAL -> "local"
                    TunnelType.REMOTE -> "remoto"
                    TunnelType.DYNAMIC_SOCKS -> "socks"
                }
            })
            Gap()
            TitanTextField("Etiqueta", draft.label, { draft = draft.copy(label = it) }, placeholder = "túnel base de datos")

            SectionHeader("Escucha")
            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                Box(Modifier.weight(1f)) { TitanTextField("Host", draft.listenHost, { draft = draft.copy(listenHost = it) }) }
                Box(Modifier.weight(1f)) { TitanTextField("Puerto", draft.listenPort.toString(), { v -> draft = draft.copy(listenPort = v.filter(Char::isDigit).toIntOrNull() ?: 0) }) }
            }

            if (draft.type != TunnelType.DYNAMIC_SOCKS) {
                SectionHeader("Destino")
                Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                    Box(Modifier.weight(1f)) { TitanTextField("Host", draft.destinationHost ?: "", { draft = draft.copy(destinationHost = it.ifBlank { null }) }) }
                    Box(Modifier.weight(1f)) { TitanTextField("Puerto", draft.destinationPort?.toString() ?: "", { v -> draft = draft.copy(destinationPort = v.filter(Char::isDigit).toIntOrNull()) }) }
                }
            }
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

/** One-line summary of a [Tunnel] used in the session editor's tunnel list. */
internal fun tunnelSummary(t: Tunnel): String = when (t.type) {
    TunnelType.DYNAMIC_SOCKS -> "socks ${t.listenHost}:${t.listenPort}"
    else -> "${t.listenHost}:${t.listenPort} → ${t.destinationHost ?: "?"}:${t.destinationPort ?: "?"}"
}

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
