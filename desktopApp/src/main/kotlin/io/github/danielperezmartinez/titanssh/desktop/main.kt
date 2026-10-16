package io.github.danielperezmartinez.titanssh.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.danielperezmartinez.titanssh.App
import io.github.danielperezmartinez.titanssh.update.AppUpdates
import javax.imageio.ImageIO

// Window/taskbar icon, generated from branding/icon.svg by branding/RenderIcon.java.
private val windowIcon = BitmapPainter(
    ImageIO.read(checkNotNull(object {}.javaClass.getResource("/titan-ssh.png"))).toComposeImageBitmap(),
)

fun main() = application {
    // ADR-0025: installing an update from Acerca de exits the app, and closing
    // the window first hands a ready update to its installer.
    AppUpdates.exitApp = ::exitApplication
    Window(
        onCloseRequest = {
            AppUpdates.updater.beforeExit()
            exitApplication()
        },
        title = "titan-ssh",
        icon = windowIcon,
    ) {
        App()
    }
}
