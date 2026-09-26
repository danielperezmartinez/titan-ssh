---
Nombre: 'Respetar las barras del sistema en Android'
Estado: 'Hecha'
Resumen: 'En Android el título "titan-ssh" de la app se pinta encima de la barra de estado (sobre la hora y los iconos). MainActivity llama a enableEdgeToEdge(), pero la UI compartida no deja el hueco de las barras del sistema: solo aplica imePadding en el terminal. Hay que añadir el padding de safeDrawing o systemBars en la raíz de la UI, sin romper el ajuste del teclado, y comprobar también la barra de navegación inferior y la orientación horizontal.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-09-24T12:45:00+02:00
Última modificación: 2026-09-26T16:55:00+02:00
---

# Respetar las barras del sistema en Android

## Objetivo

Que ningún elemento de la UI quede tapado por la barra de estado ni por la de
navegación en Android, como exige el modo edge-to-edge obligatorio desde
Android 15.

## Contexto

- Detectado el 2026-09-24 al verificar el paso 1 de
  [[Seguimiento de tareas pendientes]] en el emulador `Pixel_9_Pro_XL`. En la
  captura, el título "titan-ssh" sale solapado con la hora y los iconos de la
  barra de estado.
- `androidApp/.../android/MainActivity.kt` llama a `enableEdgeToEdge()` antes de
  `setContent { App() }`.
- La UI compartida (`shared/.../ui/`) solo gestiona los insets del teclado:
  `imePadding()` en `TerminalView`, junto con `adjustResize` (ver
  [[Terminal multipestaña con sesiones simultáneas]] y la memoria
  `android-ime-terminal`).
- Consecuencia vista el 2026-09-24: el botón `[i]` (Acerca de) de la cabecera
  queda en parte bajo la barra de estado y los toques en su mitad superior no
  llegan a la app. Comprobar que responde entero al terminar.
- En escritorio los insets son cero, así que un `windowInsetsPadding` en
  `commonMain` no le afecta.

## Criterios de finalización

- Padding de insets (`WindowInsets.safeDrawing` o `systemBars`) aplicado una
  sola vez en la raíz de la UI (`App()` o el contenedor de las dos áreas), sin
  duplicarse con el `imePadding()` del terminal: el teclado sigue ajustando el
  terminal como hasta ahora.
- Nada queda bajo la barra de estado ni bajo la de navegación, en vertical y en
  horizontal (incluido el recorte de cámara), en el emulador y en el Pixel.
- El fondo sigue llegando hasta los bordes: la barra de estado se ve sobre el
  color de la app, no sobre una franja de otro color.
- Si se crea o cambia una superficie reutilizable, actualizar su entrada del
  Catálogo técnico.

## Verificación

Probado el 2026-09-24 en el emulador `Pixel_9_Pro_XL` (Android 15) con la APK
de release (rama `fase2-release`):

- **Vertical**: el título y `[i]` quedan por debajo de la barra de estado. Un
  toque en la mitad superior de `[i]` abre Acerca de. Nada queda bajo la barra
  de navegación.
- **Horizontal, en los dos sentidos**: el contenido se aparta del recorte de
  cámara, que cambia de lado al girar.
- **Fondo**: el color de la app llega hasta los bordes, sin franjas.
- **Teclado**: con el teclado abierto, el terminal y la fila de teclas extra
  quedan justo encima, sin hueco doble.
- Hallazgo durante la prueba: con el sistema en modo claro, `enableEdgeToEdge()`
  pintaba los iconos de las barras en oscuro sobre el fondo oscuro de la app.
  Se corrigió.

El 2026-09-26 el usuario lo probó en el Pixel físico, con la APK de release
firmada con la clave real ([[Firma y configuración de release Android]]), y
no vio ningún problema.

## Resultado

- `AppShell` (`shared/.../ui/AppShell.kt`): `windowInsetsPadding(WindowInsets.safeDrawing)`
  en la `Column` raíz, dentro del `Surface` que pinta el fondo. `safeDrawing`
  incluye el IME, así que el `imePadding()` del terminal lo encuentra ya
  consumido y no lo duplica. En escritorio los insets son cero.
- `MainActivity`: `enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), …)` en
  las dos barras, porque la app solo tiene tema oscuro.
- No se crea ni cambia ninguna superficie reutilizable del catálogo.
