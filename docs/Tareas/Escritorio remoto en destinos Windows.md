---
Nombre: 'Escritorio remoto en destinos Windows'
Estado: 'En curso'
Resumen: 'Subtarea del escritorio remoto: capturar la pantalla de un destino Windows desde el ayudante de escritorio de ADR-0016, que ya corre en la sesión interactiva del usuario. El front --screen llega a él por loopback con una marca nueva del apretón de manos de ADR-0018; el ayudante captura primero con GDI (escritorio virtual entero, funciona en todo) y después con DXGI Desktop Duplication por COM desde Go sin cgo, con GDI de respaldo, y reutiliza SendInput añadiendo la posición absoluta. Bloqueo y UAC dan el estado blocked; una sesión desconectada, E_DESKTOP_INACTIVE. Necesita un destino Windows real para probarse.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]], concretado en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21): GDI primero, escritorio virtual entero con todos los monitores.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-22T09:00:00+02:00
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
- **GDI** (hecho, sin probar en Windows): `BitBlt` del escritorio virtual en
  píxeles físicos; cursor con `GetCursorInfo` + `GetIconInfo` (color con alfa,
  color con máscara y monocromo; los píxeles invertidos, como el cursor de
  texto, van en negro con borde blanco). `Blocked` = no se puede abrir el
  escritorio de entrada o la sesión está desconectada.
- **Desktop Duplication** (hecho, sin probar en Windows): COM desde Go con
  `syscall.SyscallN`, una duplicación por salida, textura de staging o
  `MapDesktopSurface` si la imagen está en memoria del sistema; rectángulos
  sucios y movidos por `screen.Damager`. Vuelve a GDI si no hay duplicación
  (monitor girado, adaptador sin soporte, demasiadas duplicaciones) y lo
  reintenta a los 5 s; `ACCESS_LOST` se reintenta en la captura siguiente.
  El cursor y el bloqueo salen siempre de GDI, que ve todos los monitores.
- **Entrada**: `POINTER_TO` pasa a coordenadas del escritorio virtual entero
  (antes, del monitor principal).
- **Destino de pruebas**: una máquina virtual Windows 11 Enterprise de
  evaluación en el PC de desarrollo (libvirt, instalación desatendida, dos
  monitores QXL), porque el PC Windows del trabajo no es alcanzable por SSH.
  Sus datos viven fuera del repositorio.

## Criterios de finalización

- Contra Windows 10 y 11 (también por escritorio remoto): se ve la pantalla,
  se actualiza, el cursor se ve, y la entrada funciona como en X11.
- Bloquear el PC o abrir un UAC lo enseña la app, y al volver sigue la imagen.
- Probado en el emulador y confirmado por el usuario en su PC Windows.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
