---
Nombre: "TerminalWheel"
Tipo: "Utilidad"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalWheel.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: 'Decide a dónde va un desplazamiento del panel del terminal (arrastre del dedo o rueda del ratón) según el TerminalSnapshot. goesToRemote(snap) es cierto si la app pidió informes de ratón o si está en la pantalla alternativa con el desplazamiento alternativo (1007). bytes(snap, notches, column, row) codifica las muescas: rueda SGR (ESC [ < 64/65 ; x ; y M) o X10 si la app activó el ratón, flechas arriba/abajo (respetando DECCKM) en la pantalla alternativa sin ratón, y null cuando toca desplazar la scrollback local. Pura y sin Compose (TerminalWheelTest).'
Última modificación: 2026-10-20T10:45:00+02:00
---

# TerminalWheel

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalWheel.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalWheel.kt);
su cobertura está en `TerminalWheelTest`.

Lee los modos que registra [[TerminalEmulator]] y la usa [[TerminalView]]: el
`scrollable` del panel manda una muesca por cada altura de línea arrastrada, en
la fila central de la pantalla. Las flechas salen de [[TerminalKeys]].

Creada en [[Rueda del ratón para apps a pantalla completa en el terminal]].
