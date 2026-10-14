package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.Composable
import io.github.danielperezmartinez.titanssh.terminal.KeepAliveRequest

/** A desktop window counts as always visible: nothing is paused there. */
@Composable
actual fun AppVisibilityEffect(onVisible: (Boolean) -> Unit) = Unit

/** Desktop systems keep a background app's network: no service needed. */
@Composable
actual fun KeepAliveService(request: KeepAliveRequest) = Unit
