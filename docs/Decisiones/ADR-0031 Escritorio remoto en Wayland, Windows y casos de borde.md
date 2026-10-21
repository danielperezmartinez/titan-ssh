---
Nombre: 'Escritorio remoto en Wayland, Windows y casos de borde'
Número: 31
Estado: 'Aceptada'
Resumen: 'Completa ADR-0029 para llevar el escritorio remoto (opción D) a Windows y a cualquier Linux. La sesión gráfica del destino se busca en logind (/run/systemd/sessions), que dice si es X11, Wayland o un gestor de inicio, si es local o remota, si está activa y qué escritorio corre, con /proc como respaldo sin systemd. KDE y GNOME en Wayland usan una sola sesión de los portales RemoteDesktop y ScreenCast por un cliente D-Bus propio, y reciben los fotogramas por un cliente PipeWire propio en Go puro (protocolo nativo, búferes memfd, sin DMA-BUF): el agente sigue sin cgo ni dependencias. El consentimiento es el diálogo del portal, recordado con su token; el acceso sin nadie delante es un ajuste explícito que pre-autoriza al agente (en KDE con el permiso kde-authorized). Los compositores wlroots usan protocolos Wayland directos (ext-image-copy-capture o wlr-screencopy, y puntero y teclado virtuales). Windows captura en el ayudante con GDI primero y Desktop Duplication después. En todos, el escritorio entero con todos los monitores, el puntero en coordenadas lógicas con HiDPI, y un error concreto para cada caso no cubierto.'
Decisión: 'Buscar la sesión gráfica en logind; en Wayland, portal RemoteDesktop + ScreenCast con clientes D-Bus y PipeWire propios en Go puro, y protocolos wlroots directos donde no hay portal de escritorio remoto; en Windows, captura en el ayudante con GDI y luego Desktop Duplication; escritorio entero con todos los monitores; acceso desatendido solo con un ajuste explícito; y un catálogo de errores concreto por caso.'
Consecuencias: 'El escritorio remoto llega a Windows, X11, KDE, GNOME y wlroots sin cgo ni dependencias nuevas, a cambio de mantener tres clientes de protocolo propios más (D-Bus, PipeWire y Wayland). El cliente PipeWire es la pieza con más riesgo, por la variación entre versiones de PipeWire; si no aguanta, se replantea con una ADR nueva. El acceso desatendido en Wayland toca la configuración de permisos del escritorio del usuario y solo se hace cuando él lo activa, visible y reversible desde el panel del agente. El catálogo de errores del agente y de la app crece.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-21T18:50:00+02:00
Última modificación: 2026-10-21T18:50:00+02:00
---

# ADR-0031 · Escritorio remoto en Wayland, Windows y casos de borde

Completa [[ADR-0029 Escritorio remoto propio a través del agente]] sin
cambiar su decisión: tipo de sesión, protocolo de baldosas, control de flujo y
modo `--screen` siguen igual. Decide lo que esa ADR dejaba abierto para
Wayland y concreta Windows y los casos de borde.

## Contexto

La opción D funciona en Linux X11. En un destino con KDE Plasma en Wayland la
app responde que el destino no admite el escritorio remoto, y Windows todavía
no lo tiene. El objetivo es que funcione en Windows y en cualquier Linux, y
que cada caso no cubierto tenga un error claro.

Comprobado en un Plasma 6.8 en Wayland (2026-10-21):

- El portal ofrece `RemoteDesktop` versión 2 (`persist_mode` y
  `restore_token` también para la entrada, y `ConnectToEIS`) y `ScreenCast`
  versión 6.
- `org.freedesktop.host.portal.Registry.Register` deja que una app sin
  Flatpak se identifique con un app-id propio.
- El portal de KDE consulta la tabla `kde-authorized`, entrada
  `remote-desktop`, del PermissionStore. Una app con `yes` en ella no ve el
  diálogo (así funciona `krdpserver`, el servidor RDP de KDE).
- `/run/systemd/sessions/<id>` dice de cada sesión `TYPE` (`x11`, `wayland`,
  `tty`), `CLASS` (`user`, `greeter`), `SEAT`, `REMOTE`, `ACTIVE`, `STATE`,
  `DESKTOP` y `UID`, en texto plano, sin D-Bus.

### Investigación: Wayland

| Vía | Dónde | Valoración |
|---|---|---|
| **Portales `RemoteDesktop` + `ScreenCast`** | KDE, GNOME y cualquier escritorio con un backend de portal que los implemente | La única vía común a KDE y GNOME. Un solo diálogo da imagen y entrada; el token lo recuerda. Los fotogramas llegan por PipeWire. |
| API de Mutter (`org.gnome.Mutter.RemoteDesktop` / `ScreenCast`) | GNOME | La que usa `gnome-remote-desktop`, sin diálogo. Si un cliente cualquiera del bus puede usarla, sirve para el acceso desatendido en GNOME; se comprueba en su tarea. Imagen también por PipeWire. |
| KWin `ScreenShot2`, `org.gnome.Shell.Screenshot` | KDE, GNOME | Restringidas a programas autorizados por el escritorio. Descartadas. |
| `ext-image-copy-capture-v1` / `wlr-screencopy-unstable-v1` + `wlr-virtual-pointer-unstable-v1` + `virtual-keyboard-unstable-v1` | Sway, Hyprland, labwc, river y otros wlroots | Protocolo Wayland con búferes shm, fácil en Go puro y sin diálogo. Su portal (`xdg-desktop-portal-wlr`) no tiene `RemoteDesktop`, así que es la vía para ellos. |
| libei (`ConnectToEIS`) | KDE, GNOME | Entrada por un socket EIS en lugar de llamadas D-Bus. Más eficiente, pero otro protocolo que escribir; las llamadas `Notify*` del portal bastan para empezar. |

### Investigación: PipeWire desde Go

| Opción | Valoración |
|---|---|
| **Cliente propio en Go puro** | Protocolo nativo por el descriptor de `OpenPipeWireRemote`, PODs de SPA, un `client-node` que consume el stream, búferes memfd o SHM (sin DMA-BUF: KWin y Mutter vuelven a SHM si el cliente no lo ofrece) y la señal por eventfd. El agente sigue estático y sin dependencias. Es la pieza más grande y la de más riesgo (variaciones entre PipeWire 0.3 y 1.x). |
| Ayudante con cgo y libpipewire | Código más corto con `pw_stream`, pero un binario dinámico más por arquitectura que rompe la portabilidad de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y complica la CI. Plan B si el cliente propio no aguanta. |
| `purego` + `dlopen` | Dependencia nueva y binario dinámico sin ganar sobre cgo. Descartada. |

### Investigación: Windows

El ayudante de [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]
y [[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]] ya corre
en la sesión interactiva, es consciente del DPI por monitor y sabe cuándo la
entrada está bloqueada (`OpenInputDesktop`). La pantalla de bloqueo, el UAC con
escritorio seguro y Ctrl+Alt+Supr son del escritorio de Winlogon: ni se ven ni
se controlan desde el usuario (ver
[[Desbloqueo del destino Windows con un servicio de sistema]], aparcada).

## Decisión

### 1. Sesión gráfica del destino

El agente busca la sesión gráfica del usuario de la conexión en logind
(`/run/systemd/sessions/*`), y solo sin systemd (o si logind no la tiene) en el
entorno de sus procesos (`/proc`), como hasta ahora:

- Se queda con las sesiones de su `UID` con `TYPE` `x11` o `wayland` y
  `CLASS` `user`. Prefiere la activa en un asiento local (`SEAT` y
  `REMOTE=0`, `ACTIVE=1`); si no hay, una remota (xrdp y similares).
- El `DISPLAY` y el `WAYLAND_DISPLAY` salen del entorno del líder de la sesión
  o de sus procesos; `DESKTOP` (y `XDG_CURRENT_DESKTOP`) dicen si es KDE,
  GNOME o un wlroots, para elegir la vía.
- Una sesión Wayland con un `DISPLAY` de XWayland nunca se trata como X11.

### 2. Wayland

- **KDE, GNOME y otros con portal de escritorio remoto**: una sesión del portal
  `RemoteDesktop` con `ScreenCast` (puntero y teclado; todos los monitores;
  cursor como metadato, o incrustado si el backend no lo da), por un
  **cliente D-Bus propio y mínimo** (autenticación `EXTERNAL`, mensajes,
  variantes `a{sv}`, señales `Response` y paso de descriptores).
- Antes, el agente se registra con `Registry.Register` con su app-id (el de la
  app, con el sufijo del canal), para que el permiso se guarde a su nombre.
- **Fotogramas** por un **cliente PipeWire propio en Go puro**, solo
  consumidor de vídeo, con formatos BGRx/BGRA/RGBx en búferes memfd o SHM.
  Si no hay manera de que funcione en las versiones habituales, se para y se
  decide con una ADR nueva (plan B: el ayudante con cgo).
- **Entrada** con `NotifyPointerMotionAbsolute` (por stream), botones, ejes y
  `NotifyKeyboardKeysym`/`Keycode`. libei queda como mejora posterior.
- **wlroots**: si el escritorio no ofrece `RemoteDesktop` y el compositor
  anuncia los protocolos, captura con `ext-image-copy-capture-v1` (o
  `wlr-screencopy` en los que aún no lo tienen) y entrada con
  `wlr-virtual-pointer` y `virtual-keyboard`, por un **cliente Wayland propio
  y mínimo**. Sin diálogo, como X11.
- Si el compositor no tiene ninguna de las dos vías, `E_SCREEN_UNSUPPORTED`
  con el nombre del escritorio en el detalle.

### 3. Consentimiento y acceso sin nadie delante (Wayland)

- **Por defecto**: el diálogo del portal en la pantalla del destino la primera
  vez, con `persist_mode` = 2. El `restore_token` se guarda en el directorio
  de estado del agente y las siguientes sesiones no preguntan. Si nadie
  responde a tiempo o se rechaza, `E_SCREEN_CONSENT`.
- **Acceso desatendido**: un ajuste explícito que el usuario activa desde la
  app. En KDE, el agente pone su app-id a `yes` en `kde-authorized` /
  `remote-desktop`; en GNOME, solo si la API de Mutter lo permite. El panel del
  agente lo enseña y lo quita. Nunca se activa solo.

### 4. Windows

- El front `--screen` llega al ayudante con una marca nueva del apretón de
  manos de [[ADR-0018 Autenticación mutua en el punto de encuentro del agente]],
  y el ayudante sirve la sesión con el mismo `screen.Serve`.
- Captura **GDI** primero (`BitBlt` del escritorio virtual entero), que
  funciona en todo, también en máquinas virtuales y por escritorio remoto. Después
  **Desktop Duplication** por COM sin cgo, una duplicación por salida,
  con GDI de respaldo si falla.
- **Bloqueo, UAC y Ctrl+Alt+Supr**: sin imagen ni entrada. El agente lo
  avisa con un estado `blocked` en el canal de pantalla, como el del mouse pad,
  y la app conserva la última imagen atenuada con el motivo.
- **Sesión desconectada** (escritorio remoto cerrado sin cerrar sesión, o
  cambio rápido de usuario): se detecta con `WTSQuerySessionInformation` y da
  `E_DESKTOP_DISCONNECTED`.

### 5. Varios monitores, HiDPI y cambios de pantalla

- El escritorio entero en todos los sistemas, como ya hace X11: en Wayland un
  stream por monitor, compuesto por el agente según su posición; en Windows el
  escritorio virtual. Elegir un monitor queda para una trama posterior.
- La imagen va en píxeles físicos. El agente convierte `POINTER_TO` a las
  coordenadas que pida cada sistema: lógicas del stream en Wayland (con su
  escala), normalizadas del escritorio virtual en Windows.
- Conectar o quitar un monitor y cambiar resolución o escala mandan
  `SCREEN_SIZE` y un fotograma entero.

### 6. Catálogo de errores

Cada caso no cubierto termina con un código del agente y un texto concreto en
la app (`AgentDiagnostics.kt`). El detalle del agente nombra el escritorio o
la causa:

| Código | Caso |
|---|---|
| `E_NO_DESKTOP` | El usuario no tiene sesión gráfica: nadie ha iniciado sesión, o solo está la pantalla de inicio. |
| `E_DESKTOP_INACTIVE` | La sesión del usuario existe pero no está en pantalla: otro usuario tiene el asiento (cambio de usuario) o, en Windows, está desconectada. |
| `E_SCREEN_UNSUPPORTED` | El sistema o el compositor no tiene ninguna vía (macOS, BSD, un Wayland sin portal de escritorio remoto ni protocolos wlroots) o el agente es el estable. |
| `E_SCREEN_CONSENT` | El diálogo del portal se rechazó o nadie lo aceptó a tiempo. |
| `E_SCREEN_CAPTURE` | La vía existe pero falló (PipeWire, portal o Desktop Duplication), con el detalle del fallo. |

La pantalla bloqueada no es un error: en X11 y Wayland se ve y se controla
el bloqueo (si el compositor lo deja); en Windows es el estado `blocked`.

## Alternativas consideradas

- **Ayudante con cgo para PipeWire**: plan B. Queda descartado mientras el
  cliente propio funcione (ver la tabla).
- **`godbus`** en lugar de un cliente D-Bus propio: es maduro y BSD, pero
  añade una dependencia; las llamadas que hacen falta son pocas, como pasó
  con X11 frente a `xgb`.
- **uinput para la entrada en Wayland**: exige preparar el sistema con root.
  Se descartó en [[Mouse pad en destinos Linux Wayland]] por el mismo motivo.
- **Solo el monitor principal**: más simple en Windows y Wayland, pero
  distinto de X11 y peor para quien trabaja con dos pantallas.
- **Pre-autorizar siempre en KDE**: quita el diálogo sin que el usuario lo
  sepa. Solo con el ajuste explícito.

## Consecuencias

- Positivas: escritorio remoto en Windows, X11, KDE, GNOME y wlroots con el
  mismo agente estático; el mismo protocolo y la misma vista en la app; un
  error claro en cada caso no cubierto; los clientes D-Bus y del portal sirven
  también para [[Mouse pad en destinos Linux Wayland]].
- Negativas / compromisos: tres clientes de protocolo propios más que
  mantener (D-Bus, PipeWire, Wayland); el cliente PipeWire puede romperse con
  versiones nuevas de PipeWire y necesita pruebas en varias; el acceso
  desatendido toca permisos del escritorio del usuario; en GNOME puede que no
  haya acceso desatendido; Windows bloqueado sigue sin verse.
