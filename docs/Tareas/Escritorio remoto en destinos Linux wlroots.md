---
Nombre: 'Escritorio remoto en destinos Linux wlroots'
Estado: 'Pendiente'
Resumen: 'Escritorio remoto en compositores Wayland basados en wlroots y afines (Sway, Hyprland, labwc, river), cuyo portal no tiene RemoteDesktop. Vía de ADR-0031: un cliente Wayland propio y mínimo en Go puro que captura con ext-image-copy-capture-v1 (o wlr-screencopy en los que aún no lo tienen) en búferes shm y mete la entrada con wlr-virtual-pointer y virtual-keyboard, sin diálogo, como X11. Se prueba con sway headless en un contenedor.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21).'
Bloqueada:
  - "[[Sesión gráfica del destino y errores del escritorio remoto]]"
Versión: ''
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-21T18:55:00+02:00
---

# Escritorio remoto en destinos Linux wlroots

Parte de [[Escritorio remoto a través del agente]]. Reutiliza la base de
[[Sesión gráfica del destino y errores del escritorio remoto]].

## Objetivo

Que una sesión de escritorio remoto enseñe y controle un escritorio Wayland
de la familia wlroots.

## Plan

1. Cliente Wayland mínimo: conexión a `$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY`,
   registro de globales, `wl_shm` con memfd y paso de descriptores.
2. Captura con `ext-image-copy-capture-v1` (con `ext-output-image-capture-source`)
   y, si no está, `wlr-screencopy-unstable-v1`; un `wl_output` por monitor,
   compuestos con su posición; daños para no comparar baldosas.
3. Cursor: con su sesión de captura de cursor si el compositor la tiene;
   si no, incrustado en la imagen.
4. Entrada con `zwlr_virtual_pointer_v1` (movimiento absoluto, botones, ejes)
   y `zwp_virtual_keyboard_v1` (con un mapa de teclado XKB propio para los
   caracteres que no estén en la distribución).
5. Pruebas: contenedor desechable con sway headless y sshd, tests de Go en
   vivo dentro de él.

## Criterios de finalización

- Contra sway headless: imagen, cursor y entrada como en X11, varios
  monitores y cambio de resolución.
- Probado en el emulador de Android y en la app de escritorio.
- Hyprland comprobado si hay manera de ejecutarlo sin GPU; si no, anotado.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
