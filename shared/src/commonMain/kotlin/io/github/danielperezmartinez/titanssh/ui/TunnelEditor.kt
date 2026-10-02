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
import io.github.danielperezmartinez.titanssh.config.listensOnNetwork
import io.github.danielperezmartinez.titanssh.theme.TitanColors
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

    // Listening beyond loopback needs the user's explicit consent.
    val exposed = draft.listensOnNetwork
    val canSave = draft.listenPort in 1..65535 &&
        (draft.type == TunnelType.DYNAMIC_SOCKS || (draft.destinationPort?.let { it in 1..65535 } ?: false)) &&
        (!exposed || draft.allowFromNetwork)

    EditorScaffold(
        title = if (isNew) "Nuevo túnel" else "Editar túnel",
        onBack = onBack,
        // A tunnel brought back to loopback forgets the consent, so exposing it
        // again asks again.
        onSave = { onSave(if (exposed) draft else draft.copy(allowFromNetwork = false)) },
        canSave = canSave,
        onDelete = if (isNew) null else onDelete,
        deleteQuestion = "¿Eliminar el túnel?",
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
            if (exposed) {
                Gap()
                Caption(networkWarning(draft), color = if (draft.type == TunnelType.DYNAMIC_SOCKS) TitanColors.Danger else TitanColors.Warning)
                TitanCheck("Permitir conexiones desde otros equipos", draft.allowFromNetwork) {
                    draft = draft.copy(allowFromNetwork = it)
                }
            } else if (draft.type != TunnelType.REMOTE) {
                Gap()
                Caption(
                    "Solo se puede usar desde este equipo, pero cualquier programa o app que se ejecute en él " +
                        "puede conectarse al puerto.",
                )
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

/** What a tunnel that [listensOnNetwork] lets other devices do. */
private fun networkWarning(t: Tunnel): String = when (t.type) {
    TunnelType.DYNAMIC_SOCKS ->
        "Escucha en ${t.listenHost}: cualquier equipo que llegue a esa dirección (por ejemplo, en la misma wifi) " +
            "podrá usar este proxy SOCKS, que no pide contraseña, para entrar en la red del servidor en tu nombre. " +
            "Úsalo solo en una red de confianza."
    else ->
        "Escucha en ${t.listenHost}: cualquier equipo que llegue a esa dirección (por ejemplo, en la misma wifi) " +
            "podrá usar el túnel y llegar a ${t.destinationHost ?: "su destino"} a través de tu sesión SSH."
}

/** One-line summary of a [Tunnel] used in the session editor's tunnel list. */
internal fun tunnelSummary(t: Tunnel): String = when (t.type) {
    TunnelType.DYNAMIC_SOCKS -> "socks ${t.listenHost}:${t.listenPort}"
    else -> "${t.listenHost}:${t.listenPort} → ${t.destinationHost ?: "?"}:${t.destinationPort ?: "?"}"
}

/** The note a tunnel list shows for a tunnel that [listensOnNetwork]; null otherwise. */
internal fun tunnelExposure(t: Tunnel): String? = if (t.listensOnNetwork) "abierto a la red" else null

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
