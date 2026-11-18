package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A remote desktop tab in full screen ([[Controles del visor del escritorio
 * remoto]]): the app hides its header, tabs and status strip, and the platform
 * gives it the whole display ([LocalSystemFullscreen]). [tabId] is the tab
 * shown, or null when nothing is.
 */
class ImmersiveState {
    var tabId: String? by mutableStateOf(null)
}

/**
 * The platform's half of full screen: on desktop the window takes the whole
 * screen, on Android the system bars hide. The default does nothing.
 */
val LocalSystemFullscreen = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
