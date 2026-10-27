package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.Composable

/**
 * Tells the system keyboard of the text fields in [content] to offer
 * [suggestions] or not, and never to learn what is typed in them: the terminal
 * and the mouse pad see commands, paths and passwords. Only Android has such a
 * keyboard; elsewhere it just shows [content].
 */
@Composable
internal expect fun SoftKeyboardIme(suggestions: Boolean, content: @Composable () -> Unit)
