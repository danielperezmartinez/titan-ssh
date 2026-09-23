---
Nombre: "TerminalKeys"
Tipo: "Utilidad"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalKeys.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Traducción pura de pulsaciones a los bytes que espera el PTY: teclas especiales (Esc, Tab, Enter, Backspace, flechas…), combinaciones Ctrl-x y Alt-x, y texto normal. Define además el layout de la barra de teclas accesorias de Android (Esc, Tab, Ctrl/Alt pegajosos, flechas y símbolos | / - ~). Sin dependencias de Compose ni de plataforma, para poder probarlo headless (TerminalKeysTest)."
Última modificación: 2026-09-24T12:00:00+02:00
---

# TerminalKeys

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalKeys.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalKeys.kt);
su cobertura está en `TerminalKeysTest`.

Lo consume [[TerminalView]] para convertir eventos de teclado (físico y barra
accesoria) en la entrada que va al shell vía `SessionTab.sendBytes`. La barra
accesoria Android cubre las teclas que el teclado software no trae, criterio de
[[Terminal multipestaña con sesiones simultáneas]].
