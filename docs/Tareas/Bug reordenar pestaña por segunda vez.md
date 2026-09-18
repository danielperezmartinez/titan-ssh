---
Nombre: "Bug: reordenar pestaña por segunda vez"
Estado: Hecha
Resumen: 'Bug del drag de pestañas: la primera reordenación funcionaba, pero al reordenar otra vez la pestaña hacía "snap" / se comportaba raro. Causa: `pointerInput(tab.id)` (clave estable) mantiene vivo el bloque de gesto, que capturaba las callbacks onDragStart/onDrag/onDragEnd de la PRIMERA composición, con index/tabs/geometría stale tras el primer reordenado. Arreglado con `rememberUpdatedState` sobre las tres callbacks. Verificado en dispositivo (dos reordenados seguidos OK).'
Decisiones: Corrige [[Reordenar pestañas de sesión con drag and drop]] / [[Terminal multipestaña con sesiones simultáneas]].
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-19T01:00:00+02:00
---

# Bug: reordenar pestaña por segunda vez

## Síntoma (reportado por el usuario)

El drag-and-drop reordena bien la **primera** vez. Al intentar reordenar **otra
vez**, la pestaña hace un *snap* a la primera posición / el reordenado se comporta
de forma extraña. Sospecha del usuario: "se ordenan visualmente pero no se les
modifica el índice" (a confirmar).

## Hipótesis fuerte (análisis de código, a verificar)

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`, `TabChip` usa
`Modifier.pointerInput(tab.id) { detectDragGesturesAfterLongPress(onDragStart,
onDrag, onDragEnd, onDrag) }`. La clave `pointerInput` es `tab.id`, que **no
cambia**, así que el bloque **no se reinicia** entre recomposiciones ni entre
gestos. Por eso las lambdas `onDragStart`/`onDrag`/`onDragEnd` que captura son las
de la **primera** composición: referencian un `index`, un `tabs` y un `centerOf`
**stale**. Tras el primer reordenado (que sí llama a `SessionManager.move` y cambia
el orden real), el gesto siguiente sigue usando geometría/índices viejos → destino
mal calculado → snap.

Nota: el índice **sí** se modifica de verdad (`TabList.move` reordena y actualiza
el `StateFlow`); el problema no es que no cambie, sino que el gesto usa una copia
vieja del estado. Por eso el síntoma "parece visual".

## Arreglo propuesto

Envolver las callbacks (o los valores `index`/`tabs`) con `rememberUpdatedState`
dentro de `TabChip`, de modo que el `pointerInput` de larga vida siempre invoque la
versión actual:

```kotlin
val currentOnDrag by rememberUpdatedState(onDrag)
val currentOnStart by rememberUpdatedState(onDragStart)
val currentOnEnd by rememberUpdatedState(onDragEnd)
// dentro de detectDragGesturesAfterLongPress usar current*.
```

Y/o mover la lógica de cálculo de destino para que lea siempre el `tabs`/`widths`
actuales (no los capturados). Revisar también que `startCenter`/`dragIndex` se
recalculan en cada `onDragStart`.

## Cómo reproducir / ver el problema (no se puede headless)

El gesto de arrastre no se puede verificar con tests headless. Opciones para que
quien lo arregle lo vea:

1. **Escritorio** (`:desktopApp:run`, JBR): abrir 3+ pestañas y reordenar dos veces
   con el ratón (long-press + arrastre). Es el camino más rápido para iterar sin
   dispositivo.
2. **Test de la lógica de destino**: extraer el cálculo de índice destino
   (`centerOf` + los `while`) a una función pura testeable con anchos simulados y
   una secuencia de dos reordenados, para cubrir el caso de regresión.
3. Si hace falta, instrumentar con logs el `onDrag` (índice actual, target,
   startCenter) y mirar el logcat del pixel.

## Verificación

- Build OK (`JAVA_HOME` al JBR, wrapper): `:shared:compileKotlinDesktop` +
  `:shared:compileAndroidMain` + `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- **Confirmado en dispositivo (2026-09-19, usuario)**: «reordena muy bien ahora»,
  dos reordenados seguidos sin snap.

## Resultado

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionsArea.kt`, `TabChip`
envuelve las callbacks del gesto con `rememberUpdatedState`
(`currentOnDragStart`/`currentOnDrag`/`currentOnDragEnd`) y el
`detectDragGesturesAfterLongPress` dentro del `pointerInput(tab.id)` de larga vida
invoca esas versiones actuales en vez de las capturadas en la primera composición.
Así, tras un reordenado, el siguiente gesto usa el `index`/`tabs`/geometría al día.
