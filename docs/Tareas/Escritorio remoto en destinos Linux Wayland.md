---
Nombre: 'Escritorio remoto en destinos Linux Wayland'
Estado: 'Planificando'
Resumen: 'Subtarea 3 del escritorio remoto: ver y controlar un escritorio Linux con Wayland (GNOME, KDE). Vía propuesta en ADR-0029: una sola sesión de los portales ScreenCast y RemoteDesktop de xdg-desktop-portal (un consentimiento en el destino, recordado con su token de restauración) y un cliente PipeWire propio para recibir los fotogramas, porque no hay ninguno en Go puro. Por decidir: si el cliente PipeWire se escribe a mano o se admite cgo solo para Wayland, y si se ofrece ext-image-copy-capture para los compositores wlroots.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño general en [[ADR-0029 Escritorio remoto propio a través del agente]]. Comparte el portal RemoteDesktop con [[Mouse pad en destinos Linux Wayland]].'
Bloqueada:
  - "[[Escritorio remoto en destinos Linux X11]]"
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-21T10:50:00+02:00
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
   fotograma. Opciones: protocolo nativo escrito a mano (PODs de SPA) o cgo
   con libpipewire solo en la build de Linux (rompe el binario estático de
   ADR-0009; necesita su ADR).
3. **Respaldo** para sway, Hyprland y afines: `ext-image-copy-capture-v1`
   (o `wlr-screencopy`) por el protocolo Wayland con búferes shm.
4. **Entrada**: `NotifyPointerMotionAbsolute` con el `stream` del ScreenCast,
   botones, ejes y `NotifyKeyboardKeysym`.

## Criterios de finalización

- Decidido con el usuario el camino de PipeWire.
- Contra GNOME y KDE en Wayland: imagen, cursor y entrada como en X11, con un
  solo consentimiento recordado.
- Confirmado por el usuario en un escritorio Wayland real.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
