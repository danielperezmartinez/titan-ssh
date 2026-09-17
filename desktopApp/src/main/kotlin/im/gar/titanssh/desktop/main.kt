package im.gar.titanssh.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import im.gar.titanssh.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "titan-ssh",
    ) {
        App()
    }
}
