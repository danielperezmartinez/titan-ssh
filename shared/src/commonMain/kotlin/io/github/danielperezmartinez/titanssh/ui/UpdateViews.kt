package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.danielperezmartinez.titanssh.BuildInfo
import io.github.danielperezmartinez.titanssh.config.UpdatePolicy
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import io.github.danielperezmartinez.titanssh.update.AppUpdater
import io.github.danielperezmartinez.titanssh.update.UpdateState

/** The header shows `[^]` while an update waits to be installed (ADR-0025). */
internal fun UpdateState.showsInHeader(): Boolean = this is UpdateState.Ready

/** Labels of the [UpdatePolicy] options in Ajustes. */
internal fun UpdatePolicy.label(): String = when (this) {
    UpdatePolicy.AUTO -> "Instalar automáticamente"
    UpdatePolicy.ASK -> "Preguntar antes de instalar"
}

/**
 * What the updater is doing and what can be done about it: check now, and
 * install a ready update. Installing closes the app, so with tabs open it asks
 * once more, saying how many would close. Shared by Ajustes and Acerca de.
 */
@Composable
internal fun UpdateStatus(updater: AppUpdater, policy: UpdatePolicy, tabCount: () -> Int) {
    val state by updater.state.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val openTabs = if (confirming) tabCount() else 0
    Column {
        when (val s = state) {
            is UpdateState.Unavailable -> Caption(s.reason)
            UpdateState.Idle -> Caption("Versión ${BuildInfo.VERSION}. Aún no se ha buscado ninguna actualización.")
            UpdateState.Checking -> Caption("Buscando actualizaciones…")
            UpdateState.UpToDate -> Caption("Versión ${BuildInfo.VERSION}: es la más reciente.")
            is UpdateState.Downloading -> Caption(
                "Descargando la versión ${s.version}" + (s.progress?.let { " · ${(it * 100).toInt()} %" } ?: "") + "…",
            )
            is UpdateState.Installing -> Caption("Instalando la versión ${s.version}…")
            is UpdateState.Failed -> Caption("[x] ${s.message}", color = TitanColors.Danger)
            is UpdateState.Ready -> {
                Caption(readyText(s, policy, updater.installsInBackground), color = TitanColors.Body)
                s.lastError?.let { Caption("[x] $it", color = TitanColors.Danger) }
            }
        }
        if (state !is UpdateState.Unavailable) {
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            val ready = state as? UpdateState.Ready
            if (ready != null && confirming && openTabs > 0) {
                Caption(
                    "Al instalar, la app se cierra y se pierden las $openTabs pestañas abiertas " +
                        "(las del agente de nivel 3 se recuperan al volver).",
                    color = TitanColors.Warning,
                )
                Spacer(Modifier.height(TitanDimens.SpaceSm))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
                verticalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm),
            ) {
                when {
                    ready?.needsPermission == true ->
                        TitanButton("[>] Permitir instalar", onClick = updater::requestInstallPermission, kind = ButtonKind.PRIMARY)
                    ready != null && confirming && openTabs > 0 -> {
                        TitanButton("[>] Instalar y cerrar", onClick = {
                            confirming = false
                            updater.installNow()
                        }, kind = ButtonKind.DANGER)
                        TitanButton("Cancelar", onClick = { confirming = false })
                    }
                    ready != null -> TitanButton(if (updater.installsInBackground) "[>] Instalar" else "[>] Instalar y reiniciar", onClick = {
                        if (tabCount() > 0) confirming = true else updater.installNow()
                    }, kind = ButtonKind.PRIMARY)
                }
                val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
                if (!(confirming && openTabs > 0)) TitanButton("[~] Buscar ahora", onClick = updater::checkNow, enabled = !busy)
            }
        }
    }
}

private fun readyText(s: UpdateState.Ready, policy: UpdatePolicy, inBackground: Boolean): String = when {
    s.needsPermission ->
        "La versión ${s.version} está descargada. Para instalarla, permite que titan-ssh instale apps."
    s.needsConfirmation -> "La versión ${s.version} está lista: falta que confirmes la instalación."
    policy == UpdatePolicy.AUTO && inBackground ->
        "La versión ${s.version} está lista. Se instalará sola con la app en segundo plano y sin pestañas abiertas."
    policy == UpdatePolicy.AUTO -> "La versión ${s.version} está lista. Se instalará sola al cerrar la app."
    else -> "La versión ${s.version} está lista para instalar."
}
