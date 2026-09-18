package im.gar.titanssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.createConfigStore
import im.gar.titanssh.secret.SecretProvisioner
import im.gar.titanssh.secret.createSecretStore
import im.gar.titanssh.ssh.createKnownHostsStore
import im.gar.titanssh.ssh.createSshConnector
import im.gar.titanssh.terminal.CredentialResolver
import im.gar.titanssh.terminal.SessionManager
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens
import im.gar.titanssh.theme.TitanTheme

/** Top-level product areas (visual decision: Configuración vs Sesiones). */
private enum class Area(val label: String) {
    CONFIG("Configuración"),
    SESSIONS("Sesiones"),
}

/**
 * Root of the titan-ssh UI: applies the theme and splits the app into the two
 * agreed areas. The [ConfigController] is created once from the platform
 * [createConfigStore] and shared by both areas.
 */
@Composable
fun AppShell() {
    TitanTheme {
        val scope = rememberCoroutineScope()
        val controller = remember { ConfigController(createConfigStore(), scope) }
        // One SecretStore instance backs both the read side (resolving credentials
        // at connect time) and the write side (provisioning them from the editor).
        val secretStore = remember { createSecretStore() }
        val provisioner = remember { SecretProvisioner(secretStore) }
        val sessionManager = remember {
            SessionManager(
                scope = scope,
                connector = createSshConnector(),
                credentialResolver = CredentialResolver(secretStore),
                knownHostsStore = createKnownHostsStore(),
            )
        }
        var area by remember { mutableStateOf(Area.CONFIG) }

        Surface(Modifier.fillMaxSize(), color = TitanColors.Canvas) {
            Column(Modifier.fillMaxSize()) {
                AreaHeader(current = area, onSelect = { area = it })
                Hairline()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (area) {
                        Area.CONFIG -> ConfigArea(controller, provisioner)
                        Area.SESSIONS -> SessionsArea(controller, sessionManager)
                    }
                }
            }
        }
    }
}

@Composable
private fun AreaHeader(current: Area, onSelect: (Area) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceLg, vertical = TitanDimens.SpaceMd)) {
        Text(
            text = "titan-ssh",
            style = MaterialTheme.typography.headlineSmall,
            color = TitanColors.Ink,
        )
        Spacer(Modifier.height(TitanDimens.SpaceMd))
        Row {
            Area.entries.forEach { entry ->
                AreaTab(
                    label = entry.label,
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AreaTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .background(if (selected) TitanColors.Surface else TitanColors.Canvas)
            .height(TitanDimens.TouchTarget)
            .padding(horizontal = TitanDimens.SpaceMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) TitanColors.Ink else TitanColors.Mute,
            textAlign = TextAlign.Center,
        )
    }
}
