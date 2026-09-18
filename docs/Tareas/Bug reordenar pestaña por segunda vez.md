---
Nombre: "Bug: reordenar pestaña por segunda vez"
Estado: Pendiente
Resumen: 'Bug del drag de pestañas reportado por el usuario: la primera reordenación funciona, pero al intentar reordenar otra vez la pestaña hace un "snap" a la primera posición / se comporta raro. Hipótesis fuerte (del análisis de código): el gesto captura estado stale del pointerInput. Incluye cómo reproducir/ver el problema, porque el gesto no se puede verificar headless.'
Decisiones: Corrige [[Reordenar pestañas de sesión con drag and drop]] / [[Terminal multipestaña con sesiones simultáneas]].
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-18T19:10:00+02:00
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

<Se rellena al completar: repro elegido, arreglo y comprobación de dos reordenados
seguidos.>

## Resultado

<Se rellena al completar.>
