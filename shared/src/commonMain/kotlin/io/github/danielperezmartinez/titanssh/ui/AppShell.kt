package io.github.danielperezmartinez.titanssh.ui

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
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.createConfigStore
import io.github.danielperezmartinez.titanssh.secret.SecretProvisioner
import io.github.danielperezmartinez.titanssh.secret.createSecretStore
import io.github.danielperezmartinez.titanssh.ssh.createKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import io.github.danielperezmartinez.titanssh.terminal.CredentialResolver
import io.github.danielperezmartinez.titanssh.terminal.SessionManager
import io.github.danielperezmartinez.titanssh.terminal.createAgentDeployer
import io.github.danielperezmartinez.titanssh.terminal.StartScriptAutomation
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import io.github.danielperezmartinez.titanssh.theme.TitanTheme

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
                automation = StartScriptAutomation(secretStore),
                // Level-3 agent (ADR-0008): installs & drives titan-agent for
                // AGENT sessions; degrades to level 2/1 when unavailable.
                agentDeployer = createAgentDeployer(),
            )
        }
        var area by remember { mutableStateOf(Area.CONFIG) }
        var showAbout by remember { mutableStateOf(false) }

        Surface(Modifier.fillMaxSize(), color = TitanColors.Canvas) {
            Column(Modifier.fillMaxSize()) {
                AreaHeader(
                    current = area.takeUnless { showAbout },
                    onSelect = { area = it; showAbout = false },
                    onAbout = { showAbout = true },
                )
                Hairline()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showAbout -> AboutScreen(onBack = { showAbout = false })
                        area == Area.CONFIG -> ConfigArea(controller, provisioner)
                        else -> SessionsArea(controller, sessionManager)
                    }
                }
            }
        }
    }
}

/** App title with the `[i]` About action, over the area tabs; [current] is null while About is open. */
@Composable
private fun AreaHeader(current: Area?, onSelect: (Area) -> Unit, onAbout: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceLg, vertical = TitanDimens.SpaceMd)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "titan-ssh",
                style = MaterialTheme.typography.headlineSmall,
                color = TitanColors.Ink,
                modifier = Modifier.weight(1f),
            )
            GlyphButton("[i]", onClick = onAbout, color = if (current == null) TitanColors.Accent else TitanColors.Mute)
        }
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
