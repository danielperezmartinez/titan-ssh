---
Nombre: 'Zoom del escritorio remoto con el cursor oculto en KDE Wayland'
Estado: 'Pendiente'
Estado desde: 2026-10-28
Resumen: 'Desde el Pixel, contra un KDE Plasma con Wayland y con zoom 2x, si el foco está en Konsole y el ratón se queda quieto, al rato la imagen salta a la esquina de arriba a la izquierda hasta que se vuelve a mover el ratón. No pasa contra X11 ni con el foco en otra aplicación. Sospecha: Konsole oculta el puntero en reposo y el portal (PipeWire, spa_meta_cursor) da entonces una posición (0,0) que el agente reenvía y la app usa como centro del zoom.'
Decisiones: 'Aparece al cerrar [[Escritorio remoto a través del agente]] y [[Escritorio remoto en destinos Linux Wayland]] (2026-10-28, canary 1.4.1-canary.45). El usuario lo quiere arreglado y en una tanda de pruebas aparte.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T21:00:00+01:00
Última modificación: 2026-10-28T21:00:00+01:00
---

# Zoom del escritorio remoto con el cursor oculto en KDE Wayland

## Objetivo

Que con zoom la imagen siga en el último sitio del cursor aunque el destino lo
oculte (Konsole lo esconde cuando el ratón está quieto), en lugar de saltar a
la esquina de arriba a la izquierda.

## Contexto

- Visto por el usuario en la canary 1.4.1-canary.45, desde el Pixel, contra
  `<destino-kde-wayland>`. Con el foco en otra aplicación, o contra un destino
  X11, no pasa.
- La app centra el zoom en `cursorX`/`cursorY` del último `CURSOR_POS`
  (`RemoteScreen.kt`, `screenViewport` en `MousepadView.kt`), esté visible o
  no.
- En el agente, `pipewire.Stream.readCursor` conserva la posición cuando el
  `spa_meta_cursor` llega con `id == 0`, pero toma la del metadato en otro
  caso. Hay que ver qué manda KWin al ocultarse el puntero (posición, `id`,
  bitmap vacío). `titan-screenprobe` imprime cada `CURSOR_POS`.

## Criterios de finalización

- Causa confirmada en un KDE Plasma con Wayland real.
- Con el cursor oculto, la imagen con zoom se queda donde estaba el cursor.
  Al volver a moverlo, sigue al cursor como antes.
- Sin cambios en X11, GNOME, wlroots ni Windows.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
