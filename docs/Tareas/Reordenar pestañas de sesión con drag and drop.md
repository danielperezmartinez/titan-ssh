---
Nombre: "Reordenar pestañas de sesión con drag and drop"
Estado: En curso
Resumen: 'UX del terminal: reordenar las pestañas de sesión debe ser drag-and-drop (arrastrar la pestaña), no los botones [<]/[>] actuales. Es lo más natural y libera espacio en la tira. Se eliminan esos iconos; el cierre [x] se mantiene. El modelo TabList.move(from,to) ya soporta reordenar a índice.'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]]; sigue el lenguaje visual [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T17:15:00+02:00
Última modificación: 2026-09-18T17:25:00+02:00
---

# Reordenar pestañas de sesión con drag and drop

## Objetivo

Que reordenar pestañas en el área de Sesiones sea **arrastrando la pestaña**, el
gesto natural, en vez de los botones `[<]` / `[>]` que hoy aparecen en la pestaña
activa. Además de ser más intuitivo, **libera espacio** en la tira de pestañas.

## Criterios de finalización

- Se eliminan los botones `[<]` / `[>]` de la pestaña (`TabChip` en
  `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`).
- Arrastrar una pestaña la reordena; el reordenado se refleja llamando a
  `SessionManager.move(from, to)` (el modelo puro [[TabList]] ya lo soporta).
- El cierre `[x]`, el `[+]` nueva, el split `[#]` y el fullscreen `[^]` se
  mantienen; tocar una pestaña sigue activándola.
- Convive con el `horizontalScroll` de la tira (el gesto de arrastre no debe
  romper el scroll; p. ej. arrastre tras pulsación mantenida).

## Verificación

- Build OK (`GRADLE_EXIT=0`, JBR + wrapper): `:shared:compileKotlinDesktop`,
  `:shared:compileAndroidMain`, `:desktopApp:compileKotlin`,
  `:androidApp:assembleDebug` → BUILD SUCCESSFUL. `:shared:desktopTest` sin fallos
  (la lógica de orden `TabList`/`TabListTest` no cambia; el reordenado sigue
  pasando por `SessionManager.move`).
- **Pendiente (por eso queda `En curso`)**: la interacción de arrastre no se puede
  verificar headless; falta comprobar en el pixel-9-pro-xl que arrastrar (tras
  pulsación mantenida) **reordena en vivo** con el hueco visible, que un toque sigue
  activando y que el scroll de la tira sigue funcionando.

## Resultado (implementado, a falta de verificación en dispositivo)

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`:

- Eliminados los `[<]` / `[>]` de `TabChip` (se mantiene `[x]`, `[+]`, `[#]`, `[^]`).
- `TabChip` reordena por **arrastre tras pulsación mantenida**
  (`detectDragGesturesAfterLongPress`), para no chocar con el `horizontalScroll` de
  la tira (arrastre rápido = scroll; toque = activar).
- **Reordenado en vivo (preview)**: durante el arrastre, al cruzar el centro del
  vecino se llama a `SessionManager.move` **en el momento**, así que las demás
  pestañas se recolocan mientras arrastras. La pestaña arrastrada **flota** siguiendo
  al dedo (`graphicsLayer.translationX = startCenter + pointerDx − centerOf(index)`,
  `zIndex` alto, superficie elevada) pero **su hueco permanece** en la posición donde
  va a caer, que es el comportamiento natural pedido.
- Geometría: cada pestaña reporta su ancho (`onGloballyPositioned` → `it.size.width`)
  indexado por `tab.id` (estable entre reordenados); los centros de slot se derivan
  por suma de prefijos sobre el orden actual.
- Nota técnica: en esta versión de Compose `LayoutCoordinates.positionInParent()`/
  `positionInRoot()` no resuelven; por eso la geometría se deriva solo de los
  anchos capturados (ver memoria [[compose-layout-coords]]).
