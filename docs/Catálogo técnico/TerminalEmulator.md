---
Nombre: "TerminalEmulator"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalEmulator.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Emulador VT100/xterm pragmático, libre de tipos Compose (testeable headless). feed(bytes) parsea UTF-8 completo (los glifos anchos CJK/emoji ocupan dos celdas; las marcas combinantes se descartan), controles C0, autowrap diferido (DECAWM), CSI de cursor (CUU/CUD/CUF/CUB/CNL/CPL/CUP/CHA/VPA), borrado (ED/EL/ECH), edición (IL/DL/ICH/DCH/REP), pantalla alterna (47/1047/1049, sin scrollback propio), regiones de scroll (DECSTBM), SGR (negrita, tenue, cursiva, subrayado, inverso, invisible, tachado, 16/256 colores y true-color), guardar/restaurar cursor, visibilidad del cursor (DECTCEM), teclas de cursor de aplicación (DECCKM) y pegado entre corchetes; responde a DSR y DA por takeResponses(). Marca las filas que continúan por ajuste (TerminalRow.wrapped), así que resize() reajusta la pantalla principal y el scrollback al nuevo ancho, empuja filas al scrollback o las recupera al cambiar el alto y mantiene la línea del cursor; la pantalla alterna solo se recorta. eraseLinesMatching(regex) quita líneas (salvo la del cursor) para ocultar las de la automatización. snapshot() es barato: comparte filas inmutables sin cambios y el scrollback si no creció. Fuera: DECOM, modo inserción, tab-stops, charsets. La paleta la aporta AnsiPalette."
Última modificación: 2026-09-28T11:05:00+02:00
---

# TerminalEmulator

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalEmulator.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalEmulator.kt),
el modelo de pantalla en
[TerminalModel.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalModel.kt)
(`TerminalSnapshot`, `TerminalCell`, `TermColor`) y la paleta en
[AnsiPalette.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AnsiPalette.kt).

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

Reajuste de líneas al redimensionar, glifos anchos, modos DECTCEM/DECCKM/pegado entre corchetes, respuestas DSR/DA y snapshots que comparten filas añadidos en
[[Terminal fluida con ajuste de líneas y sin rastro de la automatización]].
