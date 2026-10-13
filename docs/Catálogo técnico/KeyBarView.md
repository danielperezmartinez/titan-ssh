---
Nombre: "KeyBarView"
Tipo: "Componente UI"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/KeyBarView.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "La barra de teclas de encima del teclado y su editor. KeyBarRow pinta las teclas del usuario con scroll lateral y deja fijos a la derecha [*] (abre el editor) y [kbd] (teclado del sistema, si se le pasa); ilumina los modificadores pegajosos activos y, en la terminal, atenúa e inutiliza las teclas que solo tiene un escritorio remoto; qué hace cada pulsación lo decide quien la usa (onKey). AccessoryButton es su botón, reutilizable. KeyBarEditor es la pantalla Barra de teclas: arriba la barra (en varias líneas, para verla entera) y abajo el catálogo de KeyBar por grupos; mantener pulsada una tecla y arrastrarla la añade donde se suelta, la mueve o, al sacarla de la barra, la quita; un toque en una del catálogo la añade al final y Restablecer vuelve a la barra por defecto. Cada cambio se guarda al momento con ConfigController.setKeyBar."
Última modificación: 2026-10-13T08:20:00+02:00
---

# KeyBarView

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[KeyBarView.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/KeyBarView.kt).

- Las teclas y lo que mandan vienen de [[KeyBar]].
- La usan [[TerminalView]] (solo en Android) y [[MousepadView]]; el editor lo
  abre `AppShell` como pantalla propia y vuelve a la pestaña abierta.
- El arrastre mide los chips con `boundsInRoot` y calcula el hueco de destino
  con `insertionIndex` (filas que saltan de línea), probado en `KeyBarTest`.
- Diseño acordado en [[Barra de teclas configurable con arrastre]]; sigue
  [[Vocabulario ASCII ampliado y disciplina de color]] (`[*]` = ajustes).
