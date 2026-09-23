package io.github.danielperezmartinez.titanssh.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.danielperezmartinez.titanssh.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "titan-ssh",
    ) {
        App()
    }
}
