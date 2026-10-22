---
Nombre: 'Escritorio remoto en destinos Windows'
Estado: 'Hecha'
Resumen: 'Subtarea del escritorio remoto: capturar la pantalla de un destino Windows desde el ayudante de escritorio de ADR-0016, que ya corre en la sesión interactiva del usuario. El front --screen llega a él por loopback con una marca nueva del apretón de manos de ADR-0018; el ayudante captura primero con GDI (escritorio virtual entero, funciona en todo) y después con DXGI Desktop Duplication por COM desde Go sin cgo, con GDI de respaldo, y reutiliza SendInput añadiendo la posición absoluta. Bloqueo y UAC dan el estado blocked; una sesión desconectada, E_DESKTOP_INACTIVE. Necesita un destino Windows real para probarse.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]], concretado en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21): GDI primero, escritorio virtual entero con todos los monitores. El 2026-10-22 el usuario da por buena la prueba en la máquina virtual Windows 11: no habrá prueba en otro Windows. Hecha el 2026-10-22, en main (fd0c0ff) y en la canary.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-22T20:30:00+02:00
---

# Escritorio remoto en destinos Windows

Parte de [[Escritorio remoto a través del agente]]. Reutiliza la base común de
[[Escritorio remoto en destinos Linux X11]].

## Objetivo

Que una sesión de escritorio remoto enseñe y controle el escritorio de un
destino Windows.

## Propuesta

1. **Encuentro**: el front `--screen` (sesión 0) se conecta al ayudante como
   `--input`, con una marca nueva; el ayudante sirve la sesión de pantalla con
   el mismo codificador de baldosas que X11.
2. **Captura GDI primero** (`BitBlt` del escritorio virtual), y después
   **Desktop Duplication**: `D3D11CreateDevice`,
   `IDXGIOutput1::DuplicateOutput`, `AcquireNextFrame`, copia a una textura de
   staging y `Map`. Usar los rectángulos sucios y movidos para no comparar
   baldosas, y `GetFramePointerShape` para el cursor. Recrear la duplicación
   en `DXGI_ERROR_ACCESS_LOST` (cambio de modo, de escritorio o UAC).
3. **Respaldo GDI**: `BitBlt` del DC de la pantalla cuando Desktop
   Duplication falle (adaptadores sin soporte, algunas máquinas virtuales).
4. **Entrada**: `POINTER_TO` con `SendInput` en coordenadas absolutas del
   escritorio virtual; el resto, el inyector actual.
5. **Bloqueo y UAC**: sin imagen; la app enseña el estado `blocked`.
6. **Varios monitores y DPI**: el escritorio virtual entero (ADR-0031 §5);
   coordenadas físicas con el proceso consciente del DPI por monitor.
7. **Sesión desconectada** (escritorio remoto cerrado sin cerrar sesión,
   cambio rápido de usuario): `E_DESKTOP_INACTIVE`.

## Avance

- **Encuentro** (hecho): marca `TTNADSC2` del apretón de manos del ayudante.
  Tras el apretón, el ayudante contesta una línea JSON (vacía, o el error con
  su código del contrato) antes del protocolo de pantalla; el front la lee
  byte a byte y empalma el canal. Así un fallo del ayudante
  (`E_DESKTOP_INACTIVE`, `E_SCREEN_CAPTURE`) llega a la app como error del
  agente. Un ayudante de otra versión se sustituye como con `--input`.
- **Bloqueo**: en Windows 11 la pantalla de bloqueo (la cortina con la hora)
  está en el escritorio del usuario, así que `OpenInputDesktop` no la ve. La
  sesión cuenta como bloqueada mientras `LogonUI.exe` corre en ella
  (`WTSEnumerateProcesses`, porque LogonUI es de SYSTEM y no se puede abrir);
  la marca de bloqueo de `WTSSessionInfoEx` dice «bloqueada» siempre en
  Windows 11 y no sirve.
- **GDI** (hecho): `BitBlt` del escritorio virtual en
  píxeles físicos; cursor con `GetCursorInfo` + `GetIconInfo` (color con alfa,
  color con máscara y monocromo; los píxeles invertidos, como el cursor de
  texto, van en negro con borde blanco). `Blocked` = no se puede abrir el
  escritorio de entrada o la sesión está desconectada.
- **Desktop Duplication** (hecho): COM desde Go con
  `syscall.SyscallN`, una duplicación por salida, textura de staging o
  `MapDesktopSurface` si la imagen está en memoria del sistema; rectángulos
  sucios y movidos por `screen.Damager`. Vuelve a GDI si no hay duplicación
  (monitor girado, adaptador sin soporte, demasiadas duplicaciones) y lo
  reintenta a los 5 s; `ACCESS_LOST` se reintenta en la captura siguiente.
  El cursor y el bloqueo salen siempre de GDI, que ve todos los monitores.
  Como una duplicación solo entrega fotograma cuando su monitor cambia, uno
  que aún no lo ha hecho empieza con una captura GDI de su parte.
- **Entrada**: `POINTER_TO` pasa a coordenadas del escritorio virtual entero
  (antes, del monitor principal).
- **Destino de pruebas**: una máquina virtual Windows 11 Enterprise de
  evaluación (libvirt, instalación desatendida, dos monitores QXL). Sus datos
  viven fuera del repositorio.

## Criterios de finalización

- Contra Windows 10 y 11 (también por escritorio remoto): se ve la pantalla,
  se actualiza, el cursor se ve, y la entrada funciona como en X11.
- Bloquear el PC o abrir un UAC lo enseña la app, y al volver sigue la imagen.
- Probado en el emulador y por el usuario en la app de escritorio.

## Verificación

En la máquina virtual Windows 11 (dos monitores QXL de 1024×768), con el
agente canary y un cliente de prueba del protocolo de pantalla por SSH
(2026-10-22):

- Captura GDI y Desktop Duplication del escritorio virtual entero (los dos
  monitores), con el cursor y su forma; tests en vivo
  (`TITAN_SCREEN_LIVE=1`, `TITAN_INJECT_LIVE=1`) en el escritorio del usuario.
- `--screen` por SSH: el front lanza el ayudante (unos 2,7 s), llegan
  `SCREEN_SIZE`, baldosas y cursor; `POINTER_TO` deja el cursor en el píxel
  pedido, también en el segundo monitor.
- `blocked` con Win+L, la petición de contraseña, UAC y Ctrl+Alt+Supr, y la
  imagen vuelve después de cada uno.
- Cambio de resolución con la sesión abierta: llega el `SCREEN_SIZE` nuevo y
  siguen los fotogramas (la duplicación se rehace).
- Escala al 150 %: la imagen sigue en píxeles físicos, el cursor llega a su
  tamaño (48×48) y `POINTER_TO` es exacto.
- Sesión desconectada (`tsdiscon`): una sesión abierta pasa a `blocked` y
  vuelve al reconectar; una nueva da `E_DESKTOP_INACTIVE`.

- App de escritorio canary contra la máquina virtual (2026-10-22, probado por
  el usuario): se ven los dos monitores y funcionan ratón, teclado, clics y
  scroll; con Win+L, UAC y Ctrl+Alt+Supr la imagen se atenúa con el aviso de
  bloqueo y vuelve al terminar.
- Android en el emulador (2026-10-22): conecta, se ven los dos monitores y se
  actualizan, y la sesión sigue tras bloqueo, UAC y Ctrl+Alt+Supr.

Pendiente:

- Una vez, en la primera conexión desde Android, el monitor izquierdo salió
  negro y no se actualizaba (el derecho sí). El ayudante llevaba un rato en
  marcha y ya había servido una sesión de la app de escritorio cerrada a la
  fuerza. Al reconectar se vio bien y no se ha podido reproducir (sesiones
  nuevas, tras bloqueo, UAC, Ctrl+Alt+Supr y con los monitores apagados).
  Se mira si vuelve a aparecer.
- Windows 10 no se ha probado: solo Windows 11.

Resuelto al cerrar (2026-10-22):

- El texto de ayuda del tipo «escritorio» ya nombra Windows y todos los Linux
  (commit fd0c0ff).
- El usuario da por buena la prueba en la máquina virtual: no habrá otra en
  otro Windows.

## Resultado

El escritorio remoto funciona en destinos Windows desde el ayudante de
escritorio: el front `--screen` llega a él con una marca propia del apretón de
manos, y el ayudante captura el escritorio virtual entero con Desktop
Duplication (un monitor por salida, con GDI hasta su primer fotograma y de
respaldo) o con GDI, con el cursor y su forma, e inyecta con `SendInput` en
posición absoluta. La pantalla de bloqueo, UAC y Ctrl+Alt+Supr se avisan como
«bloqueado» y la imagen vuelve después; una sesión desconectada da
`E_DESKTOP_INACTIVE`. Probado en Windows 11 con dos monitores y escala al
150 %, desde la app de escritorio y desde Android. Está en la canary.
