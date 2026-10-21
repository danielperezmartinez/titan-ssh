package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.ScreenCapturePolicy
import io.github.danielperezmartinez.titanssh.config.ConfigController
import io.github.danielperezmartinez.titanssh.config.createConfigStore
import io.github.danielperezmartinez.titanssh.config.createStableCopier
import io.github.danielperezmartinez.titanssh.rdp.RdpSupport
import io.github.danielperezmartinez.titanssh.rdp.createRdpCertificateStore
import io.github.danielperezmartinez.titanssh.rdp.loadRdpEngine
import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstaller
import io.github.danielperezmartinez.titanssh.secret.SecretProvisioner
import io.github.danielperezmartinez.titanssh.secret.SecretRef
import io.github.danielperezmartinez.titanssh.secret.createSecretStore
import io.github.danielperezmartinez.titanssh.ssh.createKnownHostsStore
import io.github.danielperezmartinez.titanssh.ssh.createSshConnector
import io.github.danielperezmartinez.titanssh.terminal.AgentHost
import io.github.danielperezmartinez.titanssh.terminal.AgentHostAccess
import io.github.danielperezmartinez.titanssh.terminal.AgentManager
import io.github.danielperezmartinez.titanssh.terminal.AgentWatch
import io.github.danielperezmartinez.titanssh.terminal.BackgroundConnections
import io.github.danielperezmartinez.titanssh.terminal.CredentialResolver
import io.github.danielperezmartinez.titanssh.terminal.createAgentWatchStore
import io.github.danielperezmartinez.titanssh.terminal.SessionManager
import io.github.danielperezmartinez.titanssh.terminal.createAgentDeployer
import io.github.danielperezmartinez.titanssh.terminal.networkBlocked
import io.github.danielperezmartinez.titanssh.terminal.networkRestored
import io.github.danielperezmartinez.titanssh.terminal.StartScriptAutomation
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import io.github.danielperezmartinez.titanssh.theme.TitanTheme
import io.github.danielperezmartinez.titanssh.update.AppUpdater
import io.github.danielperezmartinez.titanssh.update.AppUpdates

/**
 * Top-level screens. Sesiones is the home screen; Configuración and About open
 * from the header's `[*]` and `[i]` and go back to it. The agent panel opens
 * from the launcher or from Configuración → Hosts and goes back there. The key
 * bar editor opens from the `[*]` of the bar above the keyboard and goes back
 * to the open tab.
 */
private enum class Screen { SESSIONS, CONFIG, ABOUT, AGENT, KEY_BAR }

/**
 * Root of the titan-ssh UI: applies the theme and hosts the two agreed areas,
 * Sesiones by default and Configuración behind the header's `[*]`. The
 * [ConfigController] is created once from the platform [createConfigStore] and
 * shared by both areas.
 */
@Composable
fun AppShell() {
    TitanTheme {
        val scope = rememberCoroutineScope()
        // ADR-0027: the canary starts with a copy of the stable app's configuration.
        val stableCopier = remember { createStableCopier() }
        val controller = remember {
            ConfigController(createConfigStore(), scope, prepare = {
                stableCopier?.takeUnless { it.hasOwnConfig() }?.copy()
            })
        }
        ScreenCaptureSetting(controller)
        // One SecretStore instance backs both the read side (resolving credentials
        // at connect time) and the write side (provisioning them from the editor).
        val secretStore = remember { createSecretStore() }
        val provisioner = remember { SecretProvisioner(secretStore) }
        val connector = remember { createSshConnector() }
        val credentialResolver = remember { CredentialResolver(secretStore) }
        val knownHostsStore = remember { createKnownHostsStore() }
        val keyInstaller = remember { KeyInstaller(connector, knownHostsStore) { credentialResolver.resolve(it) } }
        val agentDeployer = remember { createAgentDeployer() }
        // What the app knows about each destination's agent, and the sessions
        // still to close there ([[Transparencia y control del agente en el destino]]).
        val agentWatch = remember { AgentWatch(scope, createAgentWatchStore()) }
        // The embedded RDP client (ADR-0028); the native library loads with the first RDP tab.
        val rdpSupport = remember {
            RdpSupport(::loadRdpEngine, createRdpCertificateStore(), secret = { ref -> secretStore.get(SecretRef(ref)) })
        }
        val sessionManager = remember {
            SessionManager(
                scope = scope,
                connector = connector,
                credentialResolver = credentialResolver,
                knownHostsStore = knownHostsStore,
                automation = StartScriptAutomation(secretStore),
                // Level-3 agent (ADR-0008): installs & drives titan-agent for
                // AGENT sessions; degrades to level 2/1 when unavailable.
                agentDeployer = agentDeployer,
                agentObserver = agentWatch,
                networkRestored = networkRestored(),
                networkBlocked = networkBlocked(),
                rdpSupport = rdpSupport,
            )
        }
        // ADR-0023: a foreground service while a tab would lose its connection,
        // otherwise the tabs pause a while after the app leaves the screen.
        val background = remember { BackgroundConnections(scope, sessionManager.tabs) }
        AppVisibilityEffect(background::setForeground)
        val keepAlive by background.request.collectAsState()
        KeepAliveService(keepAlive)
        // ADR-0025: the updater learns the policy, the open tabs and whether the
        // app is on screen; it starts once the saved policy is known.
        val updater = remember { AppUpdates.updater }
        UpdaterSetting(controller, sessionManager, updater)
        AppVisibilityEffect(updater::setForeground)
        val updateState by updater.state.collectAsState()
        val openTabs = { sessionManager.tabs.value.size }
        val agents = remember {
            AgentManager(
                scope = scope,
                watch = agentWatch,
                access = AgentHostAccess(connector, credentialResolver, knownHostsStore, agentDeployer, agentWatch),
                sessions = sessionManager,
                config = controller,
            )
        }
        // The agent panel's destination, and the screen it goes back to.
        var agentHost by remember { mutableStateOf<AgentHost?>(null) }
        var agentReturn by remember { mutableStateOf(Screen.SESSIONS) }
        var screen by remember { mutableStateOf(Screen.SESSIONS) }
        // Set while Configuración is open on a session picked from the
        // launcher's "Editar", so going back lands on the launcher again.
        var editingSessionId by remember { mutableStateOf<String?>(null) }
        var backToLauncher by remember { mutableStateOf(false) }
        // Set while Configuración is open from the empty launcher's shortcut,
        // so it starts on the Sesiones tab instead of Hosts.
        var configOnSessions by remember { mutableStateOf(false) }

        Surface(Modifier.fillMaxSize(), color = TitanColors.Canvas) {
            // The canvas colour reaches the screen edges (edge-to-edge on Android);
            // the content keeps clear of the system bars, the display cutout and the
            // soft keyboard. safeDrawing includes the IME, so the terminal's own
            // imePadding finds it already consumed and does not apply it twice. On
            // desktop every inset is zero.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // The header glyphs toggle: tapping the one whose screen is open
                // goes back to Sesiones, like that screen's own [<].
                val toggle = { target: Screen ->
                    // Closing the open screen is going back; opening another
                    // one ends the trip from the launcher.
                    if (screen != target) backToLauncher = false
                    screen = if (screen == target) Screen.SESSIONS else target
                    editingSessionId = null
                    configOnSessions = false
                }
                AppHeader(
                    current = screen,
                    onAbout = { toggle(Screen.ABOUT) },
                    onConfig = { toggle(Screen.CONFIG) },
                    updateReady = updateState.showsInHeader(),
                )
                Hairline()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val home = {
                        screen = Screen.SESSIONS
                        editingSessionId = null
                        configOnSessions = false
                    }
                    val openAgent = { host: AgentHost, from: Screen ->
                        agentHost = host
                        agentReturn = from
                        // Back from the panel lands on the launcher it came from.
                        if (from == Screen.SESSIONS) backToLauncher = true
                        screen = Screen.AGENT
                    }
                    when (screen) {
                        Screen.SESSIONS -> SessionsArea(
                            controller,
                            sessionManager,
                            agents,
                            onOpenAgent = { openAgent(it, Screen.SESSIONS) },
                            onEditSession = { id ->
                                editingSessionId = id
                                backToLauncher = true
                                screen = Screen.CONFIG
                            },
                            onOpenConfig = {
                                backToLauncher = false
                                configOnSessions = true
                                screen = Screen.CONFIG
                            },
                            openLauncher = backToLauncher,
                            onConfigureKeyBar = {
                                backToLauncher = false
                                screen = Screen.KEY_BAR
                            },
                        )
                        Screen.CONFIG -> ConfigArea(
                            controller,
                            provisioner,
                            onBack = home,
                            keyInstaller = keyInstaller,
                            editSessionId = editingSessionId,
                            openOnSessions = configOnSessions,
                            onDeleteSession = { agents.delete(it) },
                            isSessionLive = { agents.isLive(it) },
                            onOpenAgent = { openAgent(it, Screen.CONFIG) },
                            openTabs = openTabs,
                        )
                        Screen.ABOUT -> AboutScreen(
                            onBack = home,
                            updater = updater,
                            controller = controller,
                            openTabs = openTabs,
                        )
                        Screen.KEY_BAR -> KeyBarEditor(controller, onBack = home)
                        Screen.AGENT -> agentHost?.let { host ->
                            AgentPanel(
                                agents = agents,
                                controller = controller,
                                host = host,
                                onBack = { screen = agentReturn },
                                onOpenSession = { resolved, instance ->
                                    sessionManager.open(resolved, instance)
                                    backToLauncher = false
                                    screen = Screen.SESSIONS
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Applies Ajustes → screen capture to the window. Its own scope, so a config
 * change recomposes only this and not the whole shell.
 */
@Composable
private fun ScreenCaptureSetting(controller: ConfigController) {
    val config by controller.state.collectAsState()
    ScreenCapturePolicy(config.settings.allowScreenCapture)
}

/**
 * Feeds the updater (ADR-0025) the saved policy and the number of open tabs,
 * and starts it once the config is loaded, so a saved "ask" is known before
 * anything could install. Its own scope, like [ScreenCaptureSetting].
 */
@Composable
private fun UpdaterSetting(controller: ConfigController, sessions: SessionManager, updater: AppUpdater) {
    val config by controller.state.collectAsState()
    val loaded by controller.loaded.collectAsState()
    val tabs by sessions.tabs.collectAsState()
    LaunchedEffect(config.settings.updates) { updater.setPolicy(config.settings.updates) }
    LaunchedEffect(tabs.size) { updater.setOpenTabs(tabs.size) }
    LaunchedEffect(loaded) { if (loaded) updater.start() }
}

/**
 * App title with the `[i]` About and `[*]` Configuración actions; the glyph of
 * the open screen is in accent (an active selection, visual decision). While an
 * update waits to be installed, `[^]` in accent leads to Acerca de, where it is
 * installed (ADR-0025).
 */
@Composable
private fun AppHeader(current: Screen, onAbout: () -> Unit, onConfig: () -> Unit, updateReady: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TitanDimens.SpaceLg, vertical = TitanDimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = AppChannel.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = TitanColors.Ink,
            modifier = Modifier.weight(1f),
        )
        if (updateReady && current != Screen.ABOUT) GlyphButton("[^]", onClick = onAbout, color = TitanColors.Accent)
        GlyphButton("[i]", onClick = onAbout, color = headerGlyphColor(current == Screen.ABOUT))
        GlyphButton("[*]", onClick = onConfig, color = headerGlyphColor(current == Screen.CONFIG))
    }
}

private fun headerGlyphColor(open: Boolean) = if (open) TitanColors.Accent else TitanColors.Mute
