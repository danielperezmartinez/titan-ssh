---
Nombre: "Quitar el ripple del terminal"
Estado: Hecha
Resumen: 'Al tocar el terminal (pantalla de sesiones) aparecía la animación ripple de Material al enfocar/hacer clic. Quitada: el `clickable` del terminal pasa a `indication = null` con un `MutableInteractionSource` propio, manteniendo el toque que enfoca/levanta el teclado. Verificado en dispositivo por el usuario.'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]]; sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T21:36:00+02:00
Última modificación: 2026-09-19T00:45:00+02:00
---

En la pantalla de sesiones, dentro de una sesión, está el terminal dónde el usuario puede ver y escribir comandos en el terminal. Pues al tocar con el dedo en el terminal hay una animación que creo que se llama ripple, no quiero que esté esa animación.

## Nota de implementación

El `clickable` del terminal (`shared/src/commonMain/kotlin/im/gar/titanssh/ui/TerminalView.kt`)
usa el indication por defecto (ripple). Usar `clickable(interactionSource, indication = null)`
(o `MutableInteractionSource` sin indicación) para eliminar el ripple manteniendo el toque
que enfoca/levanta el teclado.

## Resultado (2026-09-18)

Hecho en `TerminalView.kt`: el `clickable` del `Box` del terminal pasa a
`clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { … }`.
Se conserva el comportamiento del toque (Android: enfoca el campo oculto y llama a
`keyboardController.show()`; escritorio: `focusRequester.requestFocus()`), pero sin la
animación ripple de Material, coherente con la estética plana.

## Verificación

- Build OK (`JAVA_HOME` al JBR, wrapper, `--console=plain`):
  `:shared:compileKotlinDesktop` + `:shared:compileAndroidMain` → `BUILD SUCCESSFUL`
  (solo warnings preexistentes de `LocalClipboardManager`). `:shared:desktopTest` sin
  fallos. APK: `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`
  (`androidApp/build/outputs/apk/debug/androidApp-debug.apk`).
- **Confirmado en dispositivo (2026-09-19, usuario)**: «El ripple ya no se ve». Cerrada.
