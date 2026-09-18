---
Nombre: "Reordenar pestañas de sesión con drag and drop"
Estado: Hecha
Resumen: 'UX del terminal: reordenar las pestañas por drag-and-drop con preview en vivo (la arrastrada flota y deja su hueco), fuera los botones [<]/[>]. ENTREGADO y verificado en dispositivo por el usuario ("funciona bien"). Follow-ups abiertos aparte: animar el reflujo ([[Animar el reordenado de pestañas]]) y el bug de segundo reordenado ([[Bug reordenar pestaña por segunda vez]]).'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]]; sigue el lenguaje visual [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T17:15:00+02:00
Última modificación: 2026-09-18T19:15:00+02:00
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
- **Verificado en dispositivo (2026-09-18, usuario)**: arrastrar reordena en vivo
  con el hueco visible y "funciona bien". Se cierra como `Hecha`.
- **Follow-ups abiertos** (no bloquean, tareas propias): animar el reflujo de las
  pestañas ([[Animar el reordenado de pestañas]]) y el bug de que un **segundo**
  reordenado hace snap a la primera posición ([[Bug reordenar pestaña por segunda vez]],
  con la causa probable ya documentada: capturas stale en el `pointerInput`).

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
