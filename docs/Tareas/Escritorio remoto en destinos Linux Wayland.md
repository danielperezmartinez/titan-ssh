---
Nombre: 'Escritorio remoto en destinos Linux Wayland'
Estado: 'Pendiente'
Resumen: 'Subtarea del escritorio remoto: ver y controlar un escritorio Linux con Wayland en KDE, GNOME y otros con portal de escritorio remoto. Vía decidida en ADR-0031: una sola sesión de los portales RemoteDesktop y ScreenCast por un cliente D-Bus propio, con el agente registrado con su app-id; los fotogramas por un cliente PipeWire propio en Go puro (sin cgo, búferes memfd/SHM); todos los monitores compuestos; entrada con las llamadas Notify del portal. Consentimiento con el diálogo del portal recordado con su token, y acceso desatendido solo con un ajuste explícito (en KDE, permiso kde-authorized). Los compositores wlroots van en su propia tarea.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño general en [[ADR-0029 Escritorio remoto propio a través del agente]] y, para Wayland, en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]. El usuario elige el 2026-10-21 el cliente PipeWire en Go puro (el ayudante con cgo queda de plan B) y el acceso desatendido solo con un ajuste explícito. Los compositores wlroots pasan a [[Escritorio remoto en destinos Linux wlroots]]. Comparte el portal RemoteDesktop y el cliente D-Bus con [[Mouse pad en destinos Linux Wayland]].'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-21T19:00:00+02:00
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

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
