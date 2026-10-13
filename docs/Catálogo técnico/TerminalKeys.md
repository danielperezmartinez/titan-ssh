---
Nombre: "TerminalKeys"
Tipo: "Utilidad"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalKeys.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Traducción pura de pulsaciones a los bytes que espera el PTY: teclas especiales (Esc, Tab, Mayús+Tab, Enter, Backspace, flechas, Inicio/Fin, RePág/AvPág, Insert, Supr, F1-F12), combinaciones Ctrl-x y Alt-x, y texto normal. special(key, modifiers) codifica las teclas con modificadores como xterm (Ctrl+← = ESC [ 1 ; 5 D) y typed(text, modifiers) aplica Mayús, Ctrl y Alt pegajosos (bits InputKeys.MOD_*) a lo que se escribe; inCursorMode reescribe las flechas a ESC O x cuando el remoto pide teclas de cursor de aplicación (DECCKM) y paste enmarca el texto pegado como bracketed paste si está activo. pastedText deja del texto pegado solo los caracteres imprimibles, los tabuladores y los saltos de línea; lo usan paste y el texto de más de una tecla que llega del teclado de Android. Las teclas de la barra de encima del teclado ya no viven aquí: están en KeyBar. Sin dependencias de Compose ni de plataforma, para poder probarlo headless (TerminalKeysTest)."
Última modificación: 2026-10-13T08:20:00+02:00
---

# TerminalKeys

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalKeys.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalKeys.kt);
su cobertura está en `TerminalKeysTest`.

Lo consume [[TerminalView]] para convertir eventos de teclado (físico y barra
de teclas) en la entrada que va al shell vía `SessionTab.sendBytes`, y
[[KeyBar]] para las teclas de la barra.
