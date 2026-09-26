package io.github.danielperezmartinez.titanssh.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.danielperezmartinez.titanssh.App
import javax.imageio.ImageIO

// Window/taskbar icon, generated from branding/icon.svg by branding/RenderIcon.java.
private val windowIcon = BitmapPainter(
    ImageIO.read(checkNotNull(object {}.javaClass.getResource("/titan-ssh.png"))).toComposeImageBitmap(),
)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "titan-ssh",
        icon = windowIcon,
    ) {
        App()
    }
}
