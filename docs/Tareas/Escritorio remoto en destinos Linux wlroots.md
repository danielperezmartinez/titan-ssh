---
Nombre: 'Escritorio remoto en destinos Linux wlroots'
Estado: 'Hecha'
Resumen: 'Escritorio remoto en compositores Wayland basados en wlroots y afines (Sway, Hyprland, labwc, river), cuyo portal no tiene RemoteDesktop. Vía de ADR-0031: un cliente Wayland propio y mínimo en Go puro que captura con ext-image-copy-capture-v1 (o wlr-screencopy en los que aún no lo tienen) en búferes shm y mete la entrada con wlr-virtual-pointer y virtual-keyboard, sin diálogo, como X11. Se prueba con sway headless en un contenedor.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21). 2026-10-21: cliente en internal/wayland; monitores de escalas distintas uno al lado de otro con sus píxeles físicos; screen.Damager opcional para comparar solo lo dañado; el mouse pad en wlroots solo en la canary por ahora (decisión del usuario); Hyprland no se puede probar sin GPU. Hecha el 2026-10-22, probada por el usuario en la app de escritorio.'
Bloqueada: []
Versión: '1.5.0'
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-29T00:30:00+01:00
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

## Avance

- `internal/wayland`: cliente propio (protocolo de cable, `SCM_RIGHTS`,
  registro, `wl_output` + `xdg_output`, búferes shm en memfd). Los ids
  nuevos se reservan y se envían con un solo cerrojo (`Conn.Create`):
  libwayland corta la conexión si un id nuevo llega fuera de orden, y pasaba
  al conectar un monitor mientras se pedían fotogramas.
- `screen.Wlroots`: `ext-image-copy-capture-v1` con el cursor en su propia
  sesión de captura (o pintado si no hay `wl_pointer`), y si no,
  `wlr-screencopy-unstable-v1` (v1-v3, cursor pintado). Un fotograma pedido
  siempre en vuelo, daños acumulados entre capturas, salidas giradas y
  volteadas puestas derechas.
- `screen.Damager` (opcional, base común): Serve solo compara las baldosas
  dañadas. Lo usan también las tareas de Wayland (PipeWire) y Windows
  (Desktop Duplication).
- Monitores con escalas distintas: cada uno con sus píxeles físicos, en el
  orden de la distribución lógica; cada eje se estira solo por los monitores
  que tiene al lado (sin huecos ni solapes en distribuciones normales).
  `PointerTarget` lleva un punto de la imagen a la distribución lógica para
  `motion_absolute`.
- `inject.Wayland`: `zwlr_virtual_pointer_v1` (absoluto, relativo, botones,
  rueda por muescas) y `zwp_virtual_keyboard_v1` con un mapa XKB propio: una
  tecla por keysym (las letras con su mayúscula), 64 teclas de reserva para
  los caracteres que falten (se reenvía el mapa) y el estado de modificadores
  enviado aparte (el compositor no lo calcula en un teclado virtual).
- `--screen` prueba wlroots primero en la familia wlroots y da `errNoWay` si
  el compositor no tiene captura (sin entrada virtual, solo se ve).
- `--input` (mouse pad) en wlroots: solo en el agente de la canary por ahora.
- `tools/test-wlroots`: sway headless (pixman, sin GPU) con dos salidas, la
  segunda a escala 2, y sshd en `127.0.0.1:2228`; con `ALPINE=3.22`, sway 1.10
  solo con `wlr-screencopy`.
- Texto de `E_SCREEN_UNSUPPORTED` en la app: ya nombra sway, Hyprland y
  wlroots (acordado con la tarea de Wayland, que añadirá KDE y GNOME).
- **Hyprland**: no se puede ejecutar sin GPU. Su backend sin pantalla solo es
  secundario; como principal necesita DRM (asiento y un nodo de render para
  el asignador GBM) o anidarse en otro compositor que ofrezca
  `linux-dmabuf`, que sway con pixman no tiene. Queda sin probar.

## Verificación

2026-10-21/22:

- `go test -race ./...` y `go vet` (también `-tags canary`, FreeBSD, macOS,
  Windows y arm64) en verde; tests del cliente contra un compositor falso.
- Tests `Live` dentro de `tools/test-wlroots`, en sway 1.12
  (`ext-image-copy-capture`, cursor en sesión y pintado) y sway 1.10
  (`wlr-screencopy` v3), ocho tandas seguidas en verde: las dos salidas
  comparadas con `grim` (≥ 97 % de píxeles, el reloj avanza), daños de un
  tic del reloj, puntero absoluto en las dos salidas con el cursor de vuelta,
  clic, rueda, texto con `ñ€«»` y Mayús+A, cambio de resolución y posición,
  un tercer monitor conectado y quitado, y salidas giradas 90/180/270 y
  volteadas.
- `ScreenTransportIntegrationTest` por SSH contra el contenedor con un agente
  canary: primer fotograma 2304×800 en 59 ms, puntero y cursor, fotogramas
  con acuse y reconexión tras un corte.
- `--input` en el contenedor: el agente canary responde `INPUT_READY`; el
  estable, `E_INPUT_UNSUPPORTED`.
- **Android** (emulador, APK canary de debug, destino por `adb reverse` en
  `127.0.0.1:2228`): los dos monitores (el HiDPI más bajo, a la derecha), el
  cursor dibujado por la app, el gesto de un dedo moviendo el cursor, el
  toque como clic para dar el foco al terminal y `echo hola` tecleado con su
  salida. Con zoom 2×/3× la imagen tapaba la cabecera: no es de esta vía, se
  arregló en la base (recorte de la vista).
- **App de escritorio** canary contra el contenedor: probada por el usuario,
  todo bien.
- **Xwayland se caía con el teclado virtual** (2026-10-28, visto de paso
  en la rama `canary-input-fixes`): con el mapa del teclado virtual del
  agente activo, un cliente X que pedía los indicadores del mapa
  (`xkbcomp`, kitty al arrancar) tiraba Xwayland (segfault), y con él
  todos los programas X11 de la sesión. El mapa no declaraba indicadores;
  ahora lleva Caps, Num y Scroll Lock, y `xkbcomp -xkb :0` en sway sin
  pantalla con el teclado virtual ya no lo tira. Va en la canary; conviene
  que el usuario lo vea en su próxima prueba en sway con algún programa
  X11 abierto.

## Resultado

El escritorio remoto funciona en compositores wlroots con un cliente Wayland
propio en Go puro (`internal/wayland`), sin cgo ni dependencias: captura con
`ext-image-copy-capture-v1` (cursor en su sesión) o `wlr-screencopy-unstable-v1`
(cursor pintado), daños, varios monitores con escalas distintas, salidas
giradas, cambios de resolución y de monitores; entrada con puntero y teclado
virtuales y un mapa XKB propio. El mouse pad en wlroots está solo en la
canary. Destino de pruebas en `tools/test-wlroots`. Hyprland queda sin probar
(necesita GPU).
