---
Nombre: "Reordenar pestañas de sesión con drag and drop"
Estado: Pendiente
Resumen: 'UX del terminal: reordenar las pestañas de sesión debe ser drag-and-drop (arrastrar la pestaña), no los botones [<]/[>] actuales. Es lo más natural y libera espacio en la tira. Se eliminan esos iconos; el cierre [x] se mantiene. El modelo TabList.move(from,to) ya soporta reordenar a índice.'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]]; sigue el lenguaje visual [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T17:15:00+02:00
Última modificación: 2026-09-18T17:15:00+02:00
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

<Se rellena al completar: build de ambos targets y comprobación real del gesto en
el pixel-9-pro-xl (la interacción de arrastre necesita dispositivo).>

## Resultado

<Se rellena al completar.>
