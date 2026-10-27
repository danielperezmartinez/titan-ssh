---
Nombre: 'Captura X11 por memoria compartida'
Estado: 'Hecha'
Resumen: 'En X11 el agente lee la pantalla con GetImage por el socket de X, y el servidor X, que atiende las peticiones de una en una, deja esperando al cursor y a la inyección mientras copia la imagen. Ahora captura con MIT-SHM (ShmGetImage en bandas de 512 KB) cuando el servidor comparte memoria con el agente, y con GetImage si no. Medido con la sonda del cursor a 120 Hz: el cursor queda a ~120/s con huecos p99 de 9-12 ms (antes 13-38 ms) a 1280×800 y 2560×1440, y la captura cuesta un tercio menos.'
Decisiones: 'ShmGetImage en bandas de 512 KB: en una sola petición de toda la pantalla, el cursor esperaba más que con GetImage (p99 18-20 ms a 2560×1440), porque GetImage ya iba en bandas de 4 MB. Antes de usar el segmento se comprueba que el servidor escribe en él (una fila leída por los dos caminos sobre un segmento con su complemento): un id de segmento solo vale en un espacio IPC. Si MIT-SHM falla una vez, la sesión sigue con GetImage. El segmento se marca para borrar en cuanto el servidor lo adjunta, así que no queda huérfano aunque el agente muera.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T19:50:00+01:00
Última modificación: 2026-10-27T21:45:00+01:00
---

# Captura X11 por memoria compartida

## Objetivo

Que en destinos X11 el cursor del escritorio remoto no tenga los parones
que aún le quedan tras [[Escritorio remoto fluido al mover el ratón]]: con
la captura y el puntero ya en conexiones distintas, el cursor llega a
~115/s, pero con huecos p99 de ~25 ms y máximos de 30-36 ms, porque el
servidor X atiende un `GetImage` entero antes que las peticiones del
puntero. Crece con el tamaño de la pantalla (1440p, 4K).

## Análisis

- [[ADR-0029 Escritorio remoto propio a través del agente]] ya prevé MIT-SHM
  como optimización con `GetImage` de respaldo: solo funciona si el
  servidor X está en la misma máquina y en el mismo espacio IPC.
  `golang.org/x/sys/unix` tiene `SysvShmGet`/`SysvShmAttach`, sin
  dependencias nuevas.
- Con `ShmGetImage` el servidor escribe la imagen en un segmento
  compartido en lugar de mandarla por el socket.
- Opcional en la misma línea: la extensión DAMAGE, para comparar solo las
  baldosas que cambiaron (X11 hoy no es un `Damager`).

## Criterios de finalización

- El capturador X11 usa MIT-SHM cuando el servidor lo ofrece y vuelve a
  `GetImage` si no.
- Medido con la sonda de la tarea anterior en `tools/test-x11`: menos
  coste por captura y colas del cursor más cortas que con `GetImage`.

## Verificación

Contenedor propio desde `tools/test-x11` (Xvfb, 1280×800 y, cambiando el
tamaño de Xvfb en ese contenedor, 2560×1440). Agente base (`8deaed2`) y
nuevo alternados, 15 s por pasada, dos o tres pasadas por caso; se dan los
rangos.

- **Sonda** (desechable, fuera del repositorio, como en
  [[Escritorio remoto fluido al mover el ratón]]): habla `--screen` por
  `docker exec -i`, manda `POINTER_TO` en círculos a 120 Hz, acusa los
  fotogramas y puede retrasar los dos sentidos para simular un RTT. Además,
  dentro del contenedor, mide el coste de una captura y la latencia de
  `GetCursorImage` por una segunda conexión mientras otra captura cada 33 ms,
  como hace el agente.
- **Coste por captura** (lado del agente, con la conversión a RGB):

  | | `GetImage` | MIT-SHM |
  |---|---|---|
  | 1280×800, p50 / p99 | 2,3 / 2,7 ms | 1,5 / 1,8-2,0 ms |
  | 2560×1440, p50 / p99 | 8,2 / 8,8-9,0 ms | 5,6 / 6,2-6,7 ms |

- **Lectura del cursor mientras se captura** (latencia de
  `GetCursorImage`; sin capturar, p99 0,3 ms):

  | | `GetImage` | MIT-SHM, una petición | MIT-SHM, bandas de 512 KB |
  |---|---|---|---|
  | 1280×800, p90 / p99 / máx. | 8,4 / 12,2-12,4 / 20 ms | — | 0,25 / 0,36-0,41 / 5,5 ms |
  | 2560×1440, p90 / p99 / máx. | 6,6-7,1 / 10,6-10,8 / 11-17 ms | 0,5-12,7 / 18-20 / 20-39 ms | 0,22-0,24 / 0,35-0,69 / 14 ms |

  Con una sola `ShmGetImage` de toda la pantalla, el cursor esperaba más que
  con `GetImage`, que ya iba en bandas de 4 MB. Con bandas de 256 KB a 1 MB
  el p99 queda por debajo de 0,5 ms (con 4 MB, 1,4-11,6 ms) y la captura cuesta un
  8-15 % más que en una sola petición. Se eligieron 512 KB.
- **Sonda del cursor, de punta a punta** (cursor por segundo; hueco entre
  `CURSOR_POS` p99 y máximo; eco `POINTER_TO` → `CURSOR_POS` p90):

  | Escenario | base | nuevo |
  |---|---|---|
  | 1280×800 quieto, sin RTT | 117-118/s; 23 / 27-30 ms; eco 5,3-7,5 ms | 120/s; 9,4-10,8 / 18 ms; eco 2,2 ms |
  | 1280×800 quieto, RTT 50 ms | 114-117/s; 23-24 / 25-27 ms; eco 58-59 ms | 120/s; 10,5-11,7 / 18-19 ms; eco 54 ms |
  | 1280×800, xterm haciendo scroll, sin RTT | 120/s; 13-15 / 16-27 ms; eco 1,3-4,6 ms | 120/s; 9,4-9,5 / 11-21 ms; eco 1,2-1,3 ms |
  | 1280×800, xterm haciendo scroll, RTT 50 ms | 120/s; 13,6-14,7 / 17 ms | 120/s; 10-10,6 / 12,5-14,6 ms |
  | 2560×1440 quieto, sin RTT | 113-117/s; 24,5-29,5 / 37-57 ms; eco 7,4-8,4 ms | 120/s; 9,3-9,4 / 21-28 ms; eco 1,6-1,7 ms |
  | 2560×1440 quieto, RTT 50 ms | 110-116/s; 26-38 / 43-68 ms; eco 59-60 ms | 120/s; 10,4-10,5 / 15-22 ms; eco 53 ms |

  Los fotogramas por segundo no cambian (18-23 fps con el scroll). Con un
  xterm de 300×90 inundado de texto a 2560×1440, medido con la primera
  versión (una sola petición), los huecos no cambiaban: ahí el servidor X
  está ocupado pintando el texto; el eco p50 bajaba de 4,4 a 2,2 ms.
- **Respaldo con un servidor real en otro espacio IPC**: un segundo
  contenedor que comparte solo la red con el de pruebas (llega al socket
  abstracto de Xvfb) y tiene su propio IPC. Xvfb contesta `BadShmSeg`, el
  capturador sigue con `GetImage` y captura bien. Esa prueba encontró un
  fallo: el error del `ShmDetach` de limpieza llegaba a la primera captura
  por `GetImage`; arreglado y cubierto por un test.
- Tests: `go test -race ./...` del agente en verde, con tests nuevos contra
  un servidor X falso que adjunta el segmento de verdad (como uno local) o
  no (como uno de otro espacio IPC). `TestLiveDisplay` con
  `TITAN_X11_SHM=1` en el contenedor: MIT-SHM lee los mismos píxeles que
  `GetImage` (0 de 1 024 000 distintos). El agente compila para Windows,
  macOS, FreeBSD y arm64, 386 y arm de Linux. Sin segmentos huérfanos
  (`ipcs -m`) tras los tests.

## Resultado

- **`x11.ShmImage`** (`agent/internal/x11/shm.go`): crea un segmento SysV,
  lo adjunta al servidor (`ShmAttach`), comprueba que el servidor escribe en
  él y lee la ventana raíz con `ShmGetImage` en bandas de 512 KB. Solo en
  Linux; en el resto, `NewShmImage` falla y se usa `GetImage`. Sin
  dependencias nuevas (`golang.org/x/sys/unix`).
- **`screen.X11`** captura con MIT-SHM si el servidor lo permite y, si no, o
  si falla una vez, con `GetImage` para el resto de la sesión. El segmento
  crece si la pantalla crece y se libera al cerrar la sesión.
- En destinos X11, el cursor sigue al puntero a ~120 actualizaciones por
  segundo sin los parones que dejaba `GetImage`, también a 2560×1440.
- Queda fuera, como opción ya anotada en **Análisis**: la extensión DAMAGE
  para comparar solo las baldosas que cambiaron.
