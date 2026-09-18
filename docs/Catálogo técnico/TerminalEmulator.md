---
Nombre: "TerminalEmulator"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/terminal/TerminalEmulator.kt"
Entrada pública: "im.gar.titanssh.terminal"
Resumen: "Emulador VT100/ANSI pragmático, libre de tipos Compose (testeable headless). feed(bytes) parsea texto UTF-8, controles C0, CSI de cursor/borrado, SGR (negrita, inverso, 16 colores, indexado 38/48;5 y true-color 38/48;2) y guardar/restaurar cursor; consume-e-ignora OSC y modos privados DEC. resize(cols,rows) y snapshot() → TerminalSnapshot inmutable (scrollback acotado + rejilla + cursor). Fuera de v1: pantalla alterna, regiones de scroll, tab-stops, charsets. La paleta a color Compose la aporta AnsiPalette."
Última modificación: 2026-09-18T15:45:00+02:00
---

# TerminalEmulator

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalEmulator.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/TerminalEmulator.kt),
el modelo de pantalla en
[TerminalModel.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/TerminalModel.kt)
(`TerminalSnapshot`, `TerminalCell`, `TermColor`) y la paleta en
[AnsiPalette.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/AnsiPalette.kt).

Notas de contrato:

- **Sin Compose**: produce un `TerminalSnapshot` inmutable; el mapeo a `Color` de
  Compose lo hace `AnsiPalette` en la capa UI. Así el parser se prueba headless
  (`TerminalEmulatorTest`).
- `AnsiPalette` — índices 0..15 según [[Tema y tokens visuales]] /
  [[Tokens visuales dark-first base opencode]], más cubo 6x6x6 y grises de xterm.
- Lo alimenta [[SessionManager]] a través de `SessionTab` desde la salida del
  `SshShell` de [[SshConnector]]; lo pinta [[TerminalView]].

Entregado en [[Terminal multipestaña con sesiones simultáneas]].
