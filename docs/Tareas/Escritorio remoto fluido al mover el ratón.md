---
Nombre: 'Escritorio remoto fluido al mover el ratón'
Estado: 'Hecha'
Resumen: 'El escritorio remoto va a trompicones, sobre todo al mover el ratón (cursor e imagen), visto desde el Pixel. Medir con una sonda que mueve el puntero a 120 Hz por el canal --screen, encontrar las causas y arreglarlas en el agente y en la app, comprobándolo en X11, Wayland, wlroots y Windows. Causas medidas: el agente solo leía el cursor al ritmo de captura (~15 Hz, huecos de hasta 140 ms); en X11 captura, cursor e inyección compartían una conexión; y Android subía a la GPU la pantalla entera en cada fotograma. Arreglado en el árbol (cursor en su goroutine, dos conexiones X11, superficie Android por trozos de 256 px): cursor a ~115/s en X11 y ~60/s en wlroots, UI de Android p99 11 → 4-6 ms en el emulador. GNOME medido (cursor dentro de la imagen, 24 → 29-37 fps, topado por los ~30 fps de Mutter en el contenedor). Windows medido (cursor 9-22/s → 119,8/s). KDE no se pudo medir (su portal falla en el contenedor también con el agente original). Probado por el usuario en el Pixel con la canary del 2026-10-27: va mucho más fluido.'
Decisiones: 'El cursor va en su propia goroutine de Serve (Capturer.Cursor debe aguantar llamadas concurrentes con Capture) y se lee cada 8 ms durante 500 ms tras cada entrada del cliente, cada Interval si no; la entrada también saca la captura del ritmo de reposo y, con capturadores que saben qué cambió (Damager: wlroots, portal, DXGI), la acelera a 16 ms mientras dura; X11 se queda en 33 ms, porque cada captura lee la pantalla entera. X11 abre dos conexiones (captura; puntero = inyección y cursor). En Android la vista lee la pantalla en la fase de dibujo y la superficie va en bitmaps de 256×256 que se ponen al día en el hilo de UI.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T16:00:00+01:00
Última modificación: 2026-10-27T19:45:00+01:00
---

# Escritorio remoto fluido al mover el ratón

## Objetivo

El usuario usa el escritorio remoto
([[Escritorio remoto a través del agente]],
[[ADR-0029 Escritorio remoto propio a través del agente]]) desde el Pixel y
nota que va a trompicones, sobre todo al mover el ratón: tanto el cursor
como la imagen. Medirlo como en [[Mouse pad fluido sin tirones]], encontrar
las causas y arreglarlas.

## Criterios de finalización

- Una medida reproducible del ritmo al que el cursor y los fotogramas siguen
  a un puntero en movimiento, con y sin el RTT de una red real.
- Con los arreglos, el cursor sigue al puntero al ritmo con que la app lo
  mueve, medido con la misma prueba, en X11, Wayland (KDE/GNOME), wlroots y
  Windows.
- El lado de la app (aplicar baldosas y pintar en Android) no añade tirones,
  medido en el emulador.
- El usuario lo prueba en el Pixel con la canary y lo nota fluido.

## Análisis

Camino: gesto en `MousepadView` → `PointerMove`/`POINTER_TO` por el canal
`--screen` → `screen.Serve` (agente) inyecta → el bucle de `Serve` lee el
cursor y captura → `CURSOR_POS` y baldosas → `RemoteScreen` →
`RemoteScreenCanvas`.

- `Serve` lee el cursor una vez por vuelta del bucle, que duerme `Interval`
  (33 ms) o, tras 400 ms sin cambios en la imagen, `IdleInterval` (100 ms).
  Mover el ratón no cambia baldosas (el cursor va aparte), así que sobre un
  escritorio quieto el cursor llega a 10 Hz. La entrada no despierta el bucle.
- Con zoom, la vista se centra en el cursor remoto: toda la imagen se mueve a
  esos mismos saltos.
- En la app, cada `CURSOR_POS` cambia `ScreenView` y recompone `MousepadView`
  entero.

## Verificación

- **Sonda** (desechable, fuera del repositorio): habla el protocolo `--screen`
  por las tuberías de `docker exec -i … titan-agent --screen` (o de `ssh`),
  manda el puntero en círculos a 120 Hz (`POINTER_TO` como un ratón o
  `PointerMove` como el touchpad), contesta los acuses y puede simular un RTT
  retrasando los dos sentidos. Mide el ritmo de `CURSOR_POS`, el eco
  (`POINTER_TO` → `CURSOR_POS` con la misma posición) y el de fotogramas.
- **Línea base, X11** (`tools/test-x11`, 1280×800, 2026-10-27):

  | | sin RTT | RTT 50 ms |
  |---|---|---|
  | Cursor | 13,8/s | 17,0/s |
  | Hueco p50 / p90 / p99 | 56 / 122 / 139 ms | 50 / 112 / 121 ms |
  | Eco p50 | 19 ms | 67 ms |

- **Arreglo del agente, X11** (mismo escenario):

  | | base, sin RTT | nuevo, sin RTT | base, RTT 50 | nuevo, RTT 50 |
  |---|---|---|---|---|
  | Cursor | 13,8/s | 115,8/s | 17,0/s | 114,2/s |
  | Hueco p50 / p99 / máx. | 56 / 139 / 139 ms | 8 / 25 / 30 ms | 50 / 121 / 123 ms | 8 / 26 / 34 ms |
  | Eco p50 | 19 ms | 0,9 ms | 67 ms | 52 ms |

  Con un xterm haciendo scroll sin parar: base, cursor 22/s y 18 fps;
  nuevo, cursor 118/s y 24-26 fps. Captura y codificación de 1280×800:
  p50 7,6 ms, p90 10 ms por fotograma. Lo que queda en las colas del cursor
  es el servidor X atendiendo un `GetImage` (single-threaded): solo lo
  quitaría MIT-SHM.
- **wlroots** (`tools/test-wlroots`, 2304×800): cursor 21/s → 45-59/s, eco
  15 → 8,5 ms. El tope de ~60/s es el refresco de la salida del compositor,
  que es quien publica el cursor.
- **App Android, emulador** (`gfxinfo framestats`, 120 fotogramas, X11 con
  scroll continuo, deslizando por el touchpad; base y nuevo alternados, dos
  pasadas): hilo de UI por fotograma p50 2,5-2,8 → 1,0-1,1 ms, p99
  10,8-11,8 → 3,5-5,9 ms. El resto del tiempo es la presentación del
  emulador, que no refleja la subida de texturas de un móvil real.
- Tests: `go test -race ./...` del agente, `:shared:desktopTest` de pantalla,
  escritorio remoto y mouse pad, y `ScreenTransportIntegrationTest` contra
  `tools/test-x11`, en verde.
- **GNOME** (`tools/test-gnome`, acceso desatendido por la API de Mutter,
  2560×800): el cursor va dentro de la imagen (con él como metadatos,
  Mutter 48 deja de mandar fotogramas), así que su fluidez es la de los
  fotogramas: 24 fps con el agente original, 29 fps con el cursor aparte y
  32-37 fps capturando cada 16 ms mientras se mueve el puntero. El agente
  captura cada 16,6 ms, pero solo la mitad de las capturas traen imagen
  nueva: Mutter entrega ~30 por segundo en el monitor virtual del
  contenedor. Pedir 60 fps en el formato de PipeWire no cambió nada y se
  deshizo.
- **KDE** (`tools/test-kde`): el portal no crea la sesión en el contenedor
  (`CreateSession … response 2`), también con el agente original; es del
  entorno. La imagen va por el mismo PipeWire que en GNOME.
- `tools/test-x11`: `x11-session.sh` borra el candado y el socket que deja
  Xvfb al parar el contenedor; sin eso, tras un `docker stop` y
  `docker start` el escritorio no volvía a arrancar.
- **Windows** (VM de pruebas, Windows 11, dos monitores, 2048×768 en
  total, sin GPU: DXGI por software), por `ssh` y el ayudante de
  escritorio. Escritorio quieto: cursor 9,2-9,8/s (huecos de ~110 ms, el
  ritmo de reposo) → 119,8/s (p99 10-12 ms); eco con RTT 50 ms, 64 → 55 ms.
  Con una consola haciendo scroll: cursor 15-22/s (huecos de hasta
  128 ms) → 114-120/s (máx. 11-15 ms); fotogramas ~13 fps con los dos
  agentes, porque la VM no repinta más deprisa. A ratos la VM repinta la
  consola a la mitad de ritmo y los fotogramas bajan a 8-10 fps con muy
  pocos datos: pasa igual con el agente original y la traza del ayudante
  muestra que cada sesión termina limpia, así que es del entorno, no una
  regresión.
- **Canary** (2026-10-27, commit `52668c3`, integrado en `main` con
  `1687500`): workflow `Release` en verde y Release `canary` actualizado.
  El usuario lo prueba en el Pixel y confirma que va mucho más fluido.

## Resultado

- **Agente** (`screen.Serve`): el cursor va en su propia goroutine, que lo
  lee cada 8 ms durante 500 ms tras cada entrada del cliente (y en el
  acto al llegar la entrada) y cada `Interval` si no, sin esperar nunca a
  una captura. La entrada saca la captura del ritmo de reposo y, con
  capturadores que conocen el daño (wlroots, portal, DXGI), captura cada
  16 ms mientras dura. `Capturer.Cursor` debe aguantar llamadas a la vez
  que `Capture`.
- **X11**: dos conexiones con el servidor X, una para capturar y otra para
  el puntero (inyección y cursor).
- **App**: `MousepadView` lee la pantalla y el cursor en la fase de
  dibujo; `drawScreenSurface` sustituye a `imageBitmap`. En Android la
  superficie va en bitmaps de 256×256 que se ponen al día en el hilo de
  UI, así que cada fotograma solo sube a la GPU los trozos que cambian.
- **`tools/test-x11`**: el escritorio vuelve a arrancar tras un
  `docker stop` y `docker start`.
- Medido con una sonda que mueve el puntero a 120 Hz (tablas en
  **Verificación**): cursor de 9-22 a ~120 actualizaciones por segundo en
  Windows, de 14-17 a ~115 en X11 y de 21 a 45-59 en wlroots; en GNOME el
  cursor va en la imagen, de 24 a 29-37 fps.
- Ideas que no se hicieron: MIT-SHM en X11 (quitaría las colas que deja el
  servidor X atendiendo un `GetImage`) y la superficie por trozos en la
  app de escritorio, que sigue copiando la pantalla entera por fotograma.
