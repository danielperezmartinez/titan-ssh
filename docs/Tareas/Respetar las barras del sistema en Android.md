---
Nombre: 'Respetar las barras del sistema en Android'
Estado: 'Pendiente'
Resumen: 'En Android el título "titan-ssh" de la app se pinta encima de la barra de estado (sobre la hora y los iconos). MainActivity llama a enableEdgeToEdge(), pero la UI compartida no deja el hueco de las barras del sistema: solo aplica imePadding en el terminal. Hay que añadir el padding de safeDrawing o systemBars en la raíz de la UI, sin romper el ajuste del teclado, y comprobar también la barra de navegación inferior y la orientación horizontal.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-09-24T12:45:00+02:00
Última modificación: 2026-09-24T12:45:00+02:00
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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
