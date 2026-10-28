---
Nombre: 'Escritorio remoto en destinos Linux Wayland'
Estado: 'Hecha'
Estado desde: 2026-10-28
Resumen: 'Subtarea del escritorio remoto: ver y controlar un escritorio Linux con Wayland en KDE, GNOME y otros con portal de escritorio remoto. Vía decidida en ADR-0031: una sola sesión de los portales RemoteDesktop y ScreenCast por un cliente D-Bus propio, con el agente registrado con su app-id; los fotogramas por un cliente PipeWire propio en Go puro (sin cgo, búferes memfd/SHM); todos los monitores compuestos; entrada con las llamadas Notify del portal. Consentimiento con el diálogo del portal recordado con su token, y acceso desatendido solo con un ajuste explícito (en KDE, permiso kde-authorized). Los compositores wlroots van en su propia tarea.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño general en [[ADR-0029 Escritorio remoto propio a través del agente]] y, para Wayland, en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]. El usuario elige el 2026-10-21 el cliente PipeWire en Go puro (el ayudante con cgo queda de plan B) y el acceso desatendido solo con un ajuste explícito. Los compositores wlroots pasan a [[Escritorio remoto en destinos Linux wlroots]]. Comparte el portal RemoteDesktop y el cliente D-Bus con [[Mouse pad en destinos Linux Wayland]]. El 2026-10-21 el usuario decide: el agente crea un .desktop oculto con su app-id para que el Registry del portal lo acepte; el acceso desatendido va a la vez en la sesión «escritorio» (casilla) y en el panel del agente (estado, activar con confirmación, quitar y olvidar el permiso); el mouse pad por el portal entra en esta tarea; se prueba en su Plasma real del PC de desarrollo.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: '1.5.0'
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-29T00:30:00+01:00
---

# Escritorio remoto en destinos Linux Wayland

Parte de [[Escritorio remoto a través del agente]]. Reutiliza la base común de
[[Escritorio remoto en destinos Linux X11]].

## Objetivo

Que una sesión de escritorio remoto enseñe y controle un escritorio Linux que
corre sobre Wayland.

## Por qué es el caso difícil

Wayland no deja leer la pantalla a cualquier cliente. GNOME y KDE solo la dan
por el portal ScreenCast, con un diálogo de consentimiento en el destino, y
los fotogramas llegan por PipeWire, que no tiene cliente en Go puro.

## Propuesta

1. **Portal**: por el bus de sesión del usuario (`/run/user/<uid>/bus`),
   `RemoteDesktop.CreateSession`, `SelectDevices` (puntero y teclado),
   `ScreenCast.SelectSources` (monitor, cursor como metadato) con
   `persist_mode` = 2, y `Start`. Guardar el `restore_token` en el directorio
   de estado. Hace falta un cliente D-Bus mínimo (también para
   [[Mouse pad en destinos Linux Wayland]]).
2. **PipeWire**: `OpenPipeWireRemote` da un descriptor; conectar al nodo,
   negociar un formato BGRx con búferes memfd (`SPA_DATA_MemFd`) y leer cada
   fotograma con un cliente propio en Go puro: protocolo nativo, PODs de SPA,
   `client-node` y eventfd (decidido; el ayudante con cgo queda de plan B con
   ADR nueva). Todos los monitores, compuestos según su posición.
3. **wlroots** (sway, Hyprland y afines): en su propia tarea,
   [[Escritorio remoto en destinos Linux wlroots]].
4. **Entrada**: `NotifyPointerMotionAbsolute` con el `stream` del ScreenCast,
   botones, ejes y `NotifyKeyboardKeysym`.

## Criterios de finalización

- Decidido con el usuario el camino de PipeWire: cliente propio en Go puro
  (2026-10-21, [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]).
- Acceso desatendido con un ajuste explícito, visible y reversible en el panel
  del agente.
- Contra GNOME y KDE en Wayland: imagen, cursor y entrada como en X11, con un
  solo consentimiento recordado.
- Confirmado por el usuario en un escritorio Wayland real.

## Avance

2026-10-21/22 (rama `worktree-desktop-wayland`):

- Cliente D-Bus propio (`agent/internal/dbus`), sesión del portal
  (`agent/internal/portal`), cliente PipeWire propio en Go puro
  (`agent/internal/pipewire`: protocolo nativo, `client-node` consumidor,
  búferes memfd, activación de PipeWire 0.3 y 1.x, io asíncrona, cursor, recorte
  y daño), vía `screen_portal.go`, inyector del portal y mouse pad por el portal.
- `--remote-desktop unattended-on|unattended-off|forget`, `--screen
  --unattended` y el estado en `--status --json`; casilla en la sesión y fila
  en el panel del agente.
- En un Plasma 6.8 con Wayland (sesión de escritorio, no contenedor): diálogo la primera vez, después sin diálogo con el
  token; imagen, cursor y puntero bien; el desatendido entra sin token ni
  diálogo; «olvidar» no deja rastro del agente en el PermissionStore; sin
  respuesta al diálogo, `E_SCREEN_CONSENT` a los 90 s.
- KDE no se pudo probar en contenedor en el equipo de pruebas: ver
  `tools/test-kde/README.md`. GNOME, en `tools/test-gnome`.
- PipeWire 1.4 se cae si un `client-node` manda un cambio de propiedades sin
  propiedades: el cliente no lo hace.
- GNOME (Mutter 48, `tools/test-gnome`): el portal funciona (diálogo con
  «Allow Remote Interaction» apagado de serie; después, sin diálogo con el
  token) y la API propia de Mutter la puede usar cualquier cliente del bus de
  sesión, sin diálogo: es la vía del acceso desatendido en GNOME (una marca en
  el directorio de estado). En GNOME el cursor va incrustado: como metadato,
  Mutter 48 graba un fotograma de solo cursor en cada repintado y descarta el
  real («too early»). Los dos monitores se componen (2560×800).
- Los compositores pierden los eventos de un teclado y un puntero virtuales
  recién creados durante un momento, y el primero después: el agente espera
  700 ms desde el inicio y manda un toque de Mayúsculas y un empujón del
  puntero antes de atender la entrada.
- Teclado: solo llegan los caracteres de los niveles normal y de mayúsculas
  de la distribución del destino; con tecla muerta (á) o AltGr (€) no. Sigue
  en [[Teclear acentos y AltGr en destinos Wayland con portal]].
- App: compila (`:shared` desktop y Android) y pasan los 530 tests de
  escritorio. En el emulador, la canary de debug se conecta al GNOME de
  pruebas sin diálogo con la casilla de la sesión, y el panel del agente
  enseña el escritorio remoto, quita y vuelve a poner el desatendido (con
  confirmación). Capturas fuera del repositorio.

## Verificación

- **Prueba del usuario** (2026-10-28, canary 1.3.1-canary.42, desde el Pixel
  contra `<destino-wayland>` con KDE Plasma): `echo ñandú €` llega exacto,
  puntero e imagen fluidos y, al cerrar y volver a abrir la pestaña, ya no
  sale el diálogo. El usuario confirma que pasan todos los pasos del guion,
  también el acceso desatendido, «Olvidar el permiso» y el error a los 90 s.
- Fuera del guion, desde la app de escritorio en un Linux X11 contra el mismo
  destino: `ñandú` llega como `ñand´u` y AltGr mete un salto de línea (en
  Konsole, un cuadro sin glifo antes del `€`). Es
  [[Teclear acentos y AltGr en destinos Wayland con portal]] y no cuenta como
  fallo de esta tarea.

## Cómo probarlo

**Dónde**: la canary del móvil (el escritorio remoto todavía no está en
ninguna versión estable). Destino: un KDE o GNOME con la sesión gráfica
iniciada en Wayland (`<destino-wayland>`), con alguien delante para el
diálogo la primera vez.

**Preparación**: en la canary, una sesión de tipo «escritorio» para ese
destino, con la casilla de acceso desatendido sin marcar.

**Pasos**:

1. Abrir la sesión. En la pantalla del destino sale el diálogo del portal:
   aceptarlo (en GNOME, con «Permitir interacción remota» marcado).
2. Se ve la imagen con el cursor; mover, hacer clic y escribir `echo ñ` en un
   terminal. Los acentos con tecla muerta (á) y AltGr (€) **no** llegan
   todavía: es [[Teclear acentos y AltGr en destinos Wayland con portal]] y no
   cuenta como fallo.
3. Cerrar la pestaña y volver a abrirla: ya no sale el diálogo. El panel del
   agente dice «permiso recordado» en la fila «Escritorio remoto».
4. Activar el acceso desatendido en el panel del agente (pide confirmación)
   y abrir la sesión: entra sin diálogo. Desactivarlo después.
5. «Olvidar el permiso» en el panel: se cierran los escritorios abiertos y la
   siguiente vez vuelve a preguntar.
6. Abrir otra vez y no contestar al diálogo: a los 90 s la app enseña un
   error de consentimiento.

**Qué debe pasar**: lo que dice cada paso, con un solo diálogo mientras el
permiso esté recordado.

**Si falla**: qué paso, captura de la app y de la pantalla del destino,
escritorio y versión (Plasma, GNOME) y versión de la canary.

## Resultado

Escritorio remoto en KDE y GNOME con Wayland por los portales RemoteDesktop y
ScreenCast, con un cliente PipeWire propio, permiso recordado, acceso
desatendido explícito y «Olvidar el permiso» (ver **Avance**). El usuario lo
confirma en la canary el 2026-10-28. Llega a la estable en la 1.5.0
([[Pasar a la estable el escritorio remoto y la instalación de la clave]]). Los acentos y AltGr siguen en
[[Teclear acentos y AltGr en destinos Wayland con portal]].
