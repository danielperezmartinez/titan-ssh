package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import io.github.danielperezmartinez.titanssh.terminal.KeepAliveRequest

/** A desktop window counts as always visible: nothing is paused there. */
@Composable
actual fun AppVisibilityEffect(onVisible: (Boolean) -> Unit) = Unit

/** Whether the app's window is minimized; the desktop entry point provides it from its window state. */
val LocalWindowMinimized = compositionLocalOf { false }

/** While the window is not minimized, focused or not. */
@Composable
actual fun appOnScreen(): Boolean = !LocalWindowMinimized.current

/** Desktop systems keep a background app's network: no service needed. */
@Composable
actual fun KeepAliveService(request: KeepAliveRequest) = Unit
