---
Nombre: "TerminalEmulator"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/terminal/TerminalEmulator.kt"
Entrada pública: "im.gar.titanssh.terminal"
Resumen: "Emulador VT100/ANSI pragmático, libre de tipos Compose (testeable headless). feed(bytes) parsea texto UTF-8, controles C0, CSI de cursor/borrado (ED/EL/ECH), edición de líneas/caracteres (IL/DL/ICH/DCH), pantalla alterna (47/1047/1049, sin scrollback propio), regiones de scroll (DECSTBM) con LF/RI/SU/SD conscientes de la región, SGR (negrita, inverso, 16 colores, indexado 38/48;5 y true-color 38/48;2) y guardar/restaurar cursor; consume-e-ignora OSC y los modos privados DEC que no maneja. resize(cols,rows) (redimensiona también la pantalla guardada; resetea la región) y snapshot() → TerminalSnapshot inmutable (scrollback acotado + rejilla + cursor). Suficiente para apps de pantalla completa (tmux/screen, vim, less, htop). Aún fuera: origin mode (DECOM), tab-stops, charsets. La paleta a color Compose la aporta AnsiPalette."
Última modificación: 2026-09-19T17:30:00+02:00
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
- **Pantalla alterna y regiones de scroll** (añadidas para el nivel 2,
  [[Resiliencia nivel 2 auto-tmux o screen]]): las apps de pantalla completa
  (tmux/screen, vim, less, htop) usan la pantalla alterna (`1049`) para no
  ensuciar el scrollback y las regiones de scroll (`DECSTBM`) + `IL/DL/ICH/DCH`
  para editar in situ. El emulador ya las soporta, así que envolver la sesión en
  tmux se ve bien.

Entregado en [[Terminal multipestaña con sesiones simultáneas]]; pantalla alterna,
regiones de scroll y edición de líneas/caracteres añadidas en
[[Resiliencia nivel 2 auto-tmux o screen]].
