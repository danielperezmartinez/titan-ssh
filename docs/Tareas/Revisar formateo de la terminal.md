---
Nombre: "Revisar formateo de la terminal"
Estado: Pendiente
Resumen: 'La salida de comandos no aprovecha el ancho de la pantalla: al ejecutar `ls -la` aparece desordenada con saltos de línea no deseados y ~media pantalla desaprovechada. Revisar el cálculo de columnas/ancho del render del terminal (probable subestimación del ancho de celda o del área de pintado).'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]].
Bloqueada: []
Fecha de creación: 2026-09-18T21:36:00+02:00
Última modificación: 2026-09-18T21:36:00+02:00
---

He probado a escribir ls -la y aparece todo como desordenado. Creo que lo que pasa es que el "contenedor" dónde aparece el texto no está aprovechando el máximo del ancho de la pantalla, entonces hay saltos de línea no deseados. Entiendo que saltos de líneas tienen que haber, pero hay media pantalla desaprovechada.

Adjunto captura (local, no versionada por posible dato de host): `Screenshot_20260918-213655.png`.

## Nota de implementación

Probable causa en `TerminalView.kt`: el número de columnas se calcula dividiendo el
ancho del `Box` entre el ancho de celda medido (`cellWidthPx`). Si `cellWidthPx` se
sobreestima (medida de la fuente/tamaño) o el área de medida no es la real, salen
menos columnas de las que caben → `resize(cols, rows)` pide un PTY estrecho y el
remoto envuelve pronto. Revisar la medición de la celda mono y que el `onSizeChanged`
use el ancho real disponible (padding incluido).
