package im.gar.titanssh

import androidx.compose.runtime.Composable
import im.gar.titanssh.ui.AppShell

/**
 * Shared root composable. Renders the two-area product shell (Configuración /
 * Sesiones); see [[Arquitectura de dos áreas Configuración y Sesiones]]. The
 * config area (hosts, sessions, snippets, groups) is fully wired; the Sesiones
 * area is a launcher until the multi-tab terminal task lands.
 */
@Composable
fun App() {
    AppShell()
}
