package io.github.danielperezmartinez.titanssh.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.danielperezmartinez.titanssh.App
import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.ssh.DesktopHardwareKeys
import io.github.danielperezmartinez.titanssh.ui.LocalWindowMinimized
import io.github.danielperezmartinez.titanssh.update.AppUpdates
import javax.imageio.ImageIO

// Window/taskbar icon, generated from branding/icon.svg by branding/RenderIcon.java
// (the canary's own, ADR-0027).
private val windowIcon = BitmapPainter(
    ImageIO.read(checkNotNull(object {}.javaClass.getResource("/${AppChannel.appName}.png"))).toComposeImageBitmap(),
)

fun main() {
    // Hosts that authenticate with a TPM key resolve it through this (ADR-0033).
    DesktopHardwareKeys.install()
    desktopApp()
}

private fun desktopApp() = application {
    // ADR-0025: installing an update from Acerca de exits the app, and closing
    // the window first hands a ready update to its installer.
    AppUpdates.exitApp = ::exitApplication
    val windowState = rememberWindowState()
    Window(
        onCloseRequest = {
            AppUpdates.updater.beforeExit()
            exitApplication()
        },
        state = windowState,
        title = AppChannel.displayName,
        icon = windowIcon,
    ) {
        // Work that only matters on screen (the connection probe) stops while minimized.
        CompositionLocalProvider(LocalWindowMinimized provides windowState.isMinimized) {
            App()
        }
    }
}
