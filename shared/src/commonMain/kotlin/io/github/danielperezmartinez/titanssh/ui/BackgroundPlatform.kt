package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.Composable
import io.github.danielperezmartinez.titanssh.terminal.KeepAliveRequest

/**
 * Tells [onVisible] whether the app's window is visible to the user, each time
 * that changes (ADR-0023). Desktop windows never report the background: their
 * connections are not cut there.
 */
@Composable
expect fun AppVisibilityEffect(onVisible: (Boolean) -> Unit)

/**
 * Keeps the platform's foreground service up while [request] says a tab needs
 * its connection (ADR-0023), showing how many are connected. A no-op where the
 * system does not cut a background app's network (the desktop).
 */
@Composable
expect fun KeepAliveService(request: KeepAliveRequest)
