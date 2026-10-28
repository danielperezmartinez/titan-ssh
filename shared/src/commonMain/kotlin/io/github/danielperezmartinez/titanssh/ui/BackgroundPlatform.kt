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
 * Whether the user can see the app right now, for work that only matters on
 * screen such as probing the connection's quality ([[Indicar cuando la
 * conexión deja de responder]]): on Android the activity started and the screen
 * on, on the desktop the window not minimized. Unlike [AppVisibilityEffect] it
 * pauses nothing.
 */
@Composable
expect fun appOnScreen(): Boolean

/**
 * Keeps the platform's foreground service up while [request] says a tab needs
 * its connection (ADR-0023), showing how many are connected. A no-op where the
 * system does not cut a background app's network (the desktop).
 */
@Composable
expect fun KeepAliveService(request: KeepAliveRequest)
