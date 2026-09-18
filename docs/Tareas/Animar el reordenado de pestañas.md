---
Nombre: "Animar el reordenado de pestañas"
Estado: Hecha
Resumen: 'Pulido del drag de pestañas: al reordenar, las pestañas no arrastradas saltaban a su nueva posición. Ahora cada una anima su posición de slot con un Animatable (tween 180 ms), deslizándose del slot viejo al nuevo; la arrastrada sigue flotando con el dedo (snap). Las posiciones de slot se derivan por suma de prefijos de anchos (positionInParent no resuelve aquí). Verificado en dispositivo.'
Decisiones: Pulido de [[Reordenar pestañas de sesión con drag and drop]] / [[Terminal multipestaña con sesiones simultáneas]]; sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-19T01:10:00+02:00
---

# Animar el reordenado de pestañas

## Objetivo

Que al reordenar pestañas (drag-and-drop, ver
[[Reordenar pestañas de sesión con drag and drop]]) las pestañas **no arrastradas**
se desplacen **con animación** a su nueva posición, en vez de saltar
instantáneamente como ahora. La pestaña arrastrada ya flota siguiendo al dedo; falta
suavizar el reflujo de las demás.

## Notas de implementación

- El reordenado ya es en vivo (se llama a `SessionManager.move` al cruzar el centro
  del vecino). Lo que falta es animar el cambio de posición de las celdas.
- En `TabChip`/`TabStrip` (`shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`)
  se puede animar el offset de cada pestaña hacia su posición objetivo (p. ej.
  `animate*AsState` sobre el desplazamiento, o `Modifier.animatePlacement`/
  equivalente disponible en esta versión de Compose Multiplatform).
- Coordinar con el bug de [[Bug reordenar pestaña por segunda vez]] (conviene
  arreglarlo antes o a la vez, comparten el código de gesto).

## Verificación

- Build OK (`JAVA_HOME` al JBR, wrapper): `:shared:compileKotlinDesktop` +
  `:shared:compileAndroidMain` + `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- **Confirmado en dispositivo (2026-09-19, usuario)**: «era justo lo que quería»,
  las pestañas no arrastradas se deslizan a su sitio.

## Resultado

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`:

- `TabStrip` calcula `slotLeft(index)` (suma de prefijos de los anchos capturados
  por `onGloballyPositioned`, indexados por `tab.id`) y un flag `measured` (todos los
  anchos conocidos), y los pasa a cada `TabChip`.
- `TabChip` anima con un `Animatable` (`placement`) la posición de slot: cuando un
  reordenado cambia `slotLeft`, `animateTo(slotLeft, tween(180))`; el offset aplicado
  es `placement.value − slotLeft` (empieza en el delta viejo y llega a 0). La pestaña
  arrastrada usa su `translationX` (dedo) y hace `snapTo`; también se hace `snapTo`
  mientras `!measured` para no deslizar al abrir la tira.
- No se usan coordenadas absolutas (en este montaje `positionInParent`/
  `positionInRoot` no resuelven; ver memoria `compose-layout-coords`).
