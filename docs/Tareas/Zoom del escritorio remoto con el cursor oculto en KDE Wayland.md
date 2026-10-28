---
Nombre: 'Zoom del escritorio remoto con el cursor oculto en KDE Wayland'
Estado: 'En curso'
Estado desde: 2026-10-28
Resumen: 'Desde el Pixel, contra un KDE Plasma con Wayland y con zoom 2x, si el foco está en Konsole y el ratón se queda quieto, al rato la imagen salta a la esquina de arriba a la izquierda hasta que se vuelve a mover el ratón. No pasa contra X11 ni con el foco en otra aplicación. Sospecha: Konsole oculta el puntero en reposo y el portal (PipeWire, spa_meta_cursor) da entonces una posición (0,0) que el agente reenvía y la app usa como centro del zoom.'
Decisiones: 'Aparece al cerrar [[Escritorio remoto a través del agente]] y [[Escritorio remoto en destinos Linux Wayland]] (2026-10-28, canary 1.4.1-canary.45). El usuario lo quiere arreglado y en una tanda de pruebas aparte.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T21:00:00+01:00
Última modificación: 2026-10-28T22:00:00+01:00
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

- Causa confirmada el 2026-10-28 en un KDE Plasma 6.8 con Wayland real (el PC
  de desarrollo), con `titan-screenprobe` y un log temporal del
  `spa_meta_cursor` crudo: mientras Konsole oculta el puntero, KWin manda el
  metadato con `id = 0` (y la posición vieja). `pipewire.Stream` lo leía bien
  (oculto, posición conservada), pero `screen.Composite.Cursor()`, cuando
  ningún monitor muestra el cursor, devolvía `Cursor{}` sin posición: el
  agente mandaba `CURSOR_POS 0,0 visible=false` y la app centraba el zoom en
  la esquina.
- Con el arreglo, la misma prueba da `CURSOR_POS 1070,966 visible=false`: la
  posición donde se quedó el ratón.
- Test `TestCompositeLaysOutMonitors` ampliado con el cursor oculto en todos
  los monitores. `go test ./internal/screen ./internal/pipewire ./cmd/...` en
  verde.
- La app no cambia (ya centraba el zoom en la última posición recibida), así
  que no hay prueba en el emulador.

## Resultado

`screen.Composite` recuerda dónde mostró el cursor por última vez y, cuando
ningún monitor lo muestra, lo devuelve oculto en esa posición, como ya decía
su comentario. Solo cambia el caso del cursor oculto en los destinos que
capturan por `Composite` (portal de KDE y GNOME, y wlroots); X11 y Windows no
pasan por ahí. En todos ellos el cursor oculto deja ahora el zoom donde
estaba en lugar de llevarlo a la esquina.
