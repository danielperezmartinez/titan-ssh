---
Nombre: "Quitar el ripple del terminal"
Estado: Pendiente
Resumen: 'Al tocar el terminal (pantalla de sesiones) aparece la animación ripple de Material al enfocar/hacer clic; quitarla para respetar la estética plana sin chrome de Material.'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]]; sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T21:36:00+02:00
Última modificación: 2026-09-18T21:36:00+02:00
---

En la pantalla de sesiones, dentro de una sesión, está el terminal dónde el usuario puede ver y escribir comandos en el terminal. Pues al tocar con el dedo en el terminal hay una animación que creo que se llama ripple, no quiero que esté esa animación.

## Nota de implementación

El `clickable` del terminal (`shared/src/commonMain/kotlin/im/gar/titanssh/ui/TerminalView.kt`)
usa el indication por defecto (ripple). Usar `clickable(interactionSource, indication = null)`
(o `MutableInteractionSource` sin indicación) para eliminar el ripple manteniendo el toque
que enfoca/levanta el teclado.
