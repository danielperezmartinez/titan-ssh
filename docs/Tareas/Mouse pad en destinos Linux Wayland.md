---
Nombre: 'Mouse pad en destinos Linux Wayland'
Estado: 'Planificando'
Resumen: 'Subtarea 3 del mouse pad: controlar ratón y teclado de un escritorio Linux con Wayland, que bloquea a propósito la inyección genérica. Vía principal propuesta: el portal RemoteDesktop de xdg-desktop-portal (GNOME y KDE) por el D-Bus de la sesión del usuario; no necesita root, pide consentimiento en la pantalla del PC la primera vez y puede recordarlo. Respaldo para escritorios sin portal (sway, Hyprland…): un dispositivo virtual uinput, que funciona en cualquier entorno pero exige una preparación única con root, una excepción a la regla de solo espacio de usuario de ADR-0008. Se aborda después de Windows y solo si el usuario lo pide.'
Decisiones: 'Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. Reutiliza la base común de [[Mouse pad en destinos Windows]]. La preparación con root de uinput necesita la aprobación del usuario y una ADR, porque se aparta de [[ADR-0008 Diseño del agente de resiliencia nivel 3]].'
Bloqueada:
  - "[[Mouse pad en destinos Windows]]"
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-01T18:25:00+02:00
---

# Mouse pad en destinos Linux Wayland

Parte de [[Sesión mouse pad para controlar el escritorio del destino]].
Reutiliza el tipo de sesión, el protocolo de entrada y la pestaña de gestos
de [[Mouse pad en destinos Windows]].

## Objetivo

Que una sesión mouse pad controle el ratón y el teclado de un escritorio Linux
que corre sobre Wayland.

## Por qué es el caso difícil

Wayland no deja que un cliente cualquiera mueva el ratón o escriba en otras
ventanas, y no hay un mecanismo común a todos los compositores. Hay que
pasar por el portal del escritorio o por el núcleo (uinput).

## Propuesta

1. **Detectar**: la sesión gráfica del usuario es Wayland (`loginctl` con
   `Type=wayland`, o `XDG_SESSION_TYPE`) y qué compositor corre.
2. **Vía principal: portal RemoteDesktop** (`org.freedesktop.portal.RemoteDesktop`)
   por el bus de sesión del usuario (`/run/user/<uid>/bus`):
   - `CreateSession`, `SelectDevices` (puntero y teclado) y `Start`, que
     muestra un diálogo de consentimiento en la pantalla del PC. Con
     `persist_mode` se guarda un token de restauración para no repetirlo.
   - Eventos con `NotifyPointerMotion`, `NotifyPointerButton`,
     `NotifyPointerAxis` y `NotifyKeyboardKeysym`.
   - Hace falta un cliente D-Bus en Go: a mano, sin dependencias nuevas, o con
     una librería y su ADR. Evaluar también libei/EIS (`ConnectToEIS`) en los
     escritorios que lo ofrezcan.
3. **Respaldo: uinput** para compositores sin portal RemoteDesktop:
   - un ratón y un teclado virtuales con `ioctl` sobre `/dev/uinput`, con
     `golang.org/x/sys/unix`;
   - `/dev/uinput` solo lo abre root por defecto, así que exige una
     preparación única con `sudo` (regla udev o grupo `input`). Se aparta del
     solo espacio de usuario de
     [[ADR-0008 Diseño del agente de resiliencia nivel 3]]: necesita la
     aprobación del usuario y una ADR;
   - el teclado va por códigos de tecla físicos, así que el texto depende de
     la distribución del destino.

## Criterios de finalización

- Contra un escritorio GNOME o KDE en Wayland: mover, clic izquierdo y
  derecho, arrastrar, scroll vertical y horizontal, texto con acentos y ñ,
  teclas especiales y combinaciones, con un solo consentimiento recordado.
- Decidido, con el usuario, si el respaldo con uinput entra o se descarta.
- Sin sesión Wayland iniciada, o sin portal ni uinput disponibles, error
  claro que explica qué falta.
- Probado en el emulador y confirmado por el usuario en un escritorio Wayland
  real.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
