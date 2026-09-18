package im.gar.titanssh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.resolve
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens

/**
 * The "Sesiones" area. The full multi-tab resilient terminal is its own task
 * ([[Terminal multipestaña con sesiones simultáneas]]); here it is a launcher
 * that lists the saved sessions and shows the connection each one resolves to,
 * which already proves the config → engine resolution end to end.
 */
@Composable
fun SessionsArea(controller: ConfigController) {
    val config by controller.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bodyPadding()) {
            item {
                Caption("El terminal multipestaña llega en su propia tarea. Aquí se lanzan las sesiones guardadas.")
                Spacer(Modifier.height(TitanDimens.SpaceMd))
                Hairline()
            }
            if (config.sessions.isEmpty()) {
                item { EmptyState("No hay sesiones. Créalas en Configuración → Sesiones.") }
            }
            items(config.sessions) { session ->
                val resolved = runCatching { config.resolve(session) }.getOrNull()
                val subtitle = if (resolved != null) {
                    "${resolved.endpoint.username}@${resolved.endpoint.host}:${resolved.endpoint.port}"
                } else {
                    "host no encontrado — revisa la configuración"
                }
                ListRow(
                    marker = if (resolved != null) "[>]" else "[x]",
                    title = session.name,
                    subtitle = subtitle,
                    onClick = {},
                    // Neutral until there is a live connection; success/reconnecting
                    // states arrive with the real terminal. Danger flags a config error.
                    markerColor = if (resolved != null) TitanColors.Body else TitanColors.Danger,
                    trailing = {
                        if (resolved != null) {
                            TitanButton("[>] Lanzar", onClick = { /* wired by the terminal task */ }, kind = ButtonKind.SECONDARY)
                        }
                    },
                )
                Hairline()
            }
        }
    }
}
