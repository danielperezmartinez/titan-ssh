package io.github.danielperezmartinez.titanssh.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.danielperezmartinez.titanssh.App
import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.ssh.DesktopHardwareKeys
import io.github.danielperezmartinez.titanssh.ui.LocalSystemFullscreen
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
        // A remote desktop's full screen takes the whole screen, and gives back
        // the window as it was ([[Controles del visor del escritorio remoto con barra movible]]).
        var before by remember { mutableStateOf(WindowPlacement.Floating) }
        val systemFullscreen: (Boolean) -> Unit = { on ->
            if (on && windowState.placement != WindowPlacement.Fullscreen) {
                before = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!on && windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = before
            }
        }
        CompositionLocalProvider(
            LocalWindowMinimized provides windowState.isMinimized,
            LocalSystemFullscreen provides systemFullscreen,
        ) {
            App()
        }
    }
}
