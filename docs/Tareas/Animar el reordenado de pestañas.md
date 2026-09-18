---
Nombre: "Animar el reordenado de pestañas"
Estado: Pendiente
Resumen: 'Pulido del drag de pestañas: hoy, al reordenar, las pestañas no arrastradas cambian de sitio instantáneamente (saltan). Deberían animar su desplazamiento a la nueva posición para que el movimiento sea fluido y legible.'
Decisiones: Pulido de [[Reordenar pestañas de sesión con drag and drop]] / [[Terminal multipestaña con sesiones simultáneas]]; sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-18T19:10:00+02:00
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

<Se rellena al completar: build + comprobación del gesto en el pixel-9-pro-xl.>

## Resultado

<Se rellena al completar.>
