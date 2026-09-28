---
Nombre: 'Quitar las pestañas de área y abrir Configuración desde la cabecera'
Estado: 'Hecha'
Resumen: 'Quitadas las pestañas "Configuración" y "Sesiones" de la cabecera. La app arranca en Sesiones y Configuración se abre con el glifo [*] (solo icono) a la derecha de [i], con barra [<] Configuración para volver y toggle en el glifo. UI compartida: vale igual en Android y escritorio. Verificado en el emulador Android y confirmado por el usuario.'
Decisiones: 'Aplica [[Sesiones como inicio y Configuración desde la cabecera]]; añade [*] a [[Vocabulario ASCII ampliado y disciplina de color]]. Matiza la navegación de [[Arquitectura de dos áreas Configuración y Sesiones]] sin reemplazarla.'
Bloqueada: []
Fecha de creación: 2026-09-28T11:40:00+02:00
Última modificación: 2026-09-28T11:45:00+02:00
---

# Quitar las pestañas de área y abrir Configuración desde la cabecera

Petición del usuario (2026-09-28), vista en Android:

- Quitar las pestañas "Configuración" y "Sesiones".
- Por defecto debe salir la pantalla de sesiones; a Configuración se accede con
  un botón a la derecha del `[i]`, solo icono y sin etiqueta, con el aspecto que
  encaje en las directrices visuales.

## Implementación

- `ui/AppShell.kt`: el enum `Area` y la barra de pestañas (`AreaTab`) se
  sustituyen por `Screen { SESSIONS, CONFIG, ABOUT }` con `SESSIONS` por
  defecto. `AppHeader` es una sola fila: título, `[i]` y `[*]`, en `mute` y en
  `accent` el de la pantalla abierta. Tocar el glifo de la pantalla abierta
  vuelve a Sesiones.
- `ui/ConfigArea.kt`: recibe `onBack` y muestra `TopBar("Configuración")` sobre
  sus subpestañas (no dentro de los editores, que tienen su propia barra).
- `TopBar` (`[<]` + título) pasa de `AboutScreen.kt` a `Components.kt` para
  compartirlo.

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:androidApp:assembleDebug` y `:shared:desktopTest` → `BUILD SUCCESSFUL`.
- Emulador `Pixel_9_Pro_XL` (build de debug): arranca en Sesiones (lanzadera);
  `[*]` abre Configuración con `[*]` en accent; `[<]` vuelve a Sesiones; desde
  Configuración `[i]` salta a Acerca de; tocar `[i]` otra vez vuelve a
  Sesiones.
- **Confirmado por el usuario (2026-09-28)** en el emulador. Cerrada.
