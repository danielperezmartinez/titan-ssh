package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun SoftKeyboardIme(suggestions: Boolean, content: @Composable () -> Unit) = content()
