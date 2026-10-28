---
Nombre: 'Mouse pad en destinos Linux Wayland'
Estado: 'Hecha'
Estado desde: 2026-10-28
Resumen: 'Subtarea 3 del mouse pad: controlar ratón y teclado de un escritorio Linux con Wayland, que bloquea a propósito la inyección genérica. Vía principal propuesta: el portal RemoteDesktop de xdg-desktop-portal (GNOME y KDE) por el D-Bus de la sesión del usuario; no necesita root, pide consentimiento en la pantalla del PC la primera vez y puede recordarlo. Respaldo para escritorios sin portal (sway, Hyprland…): un dispositivo virtual uinput, que funciona en cualquier entorno pero exige una preparación única con root, una excepción a la regla de solo espacio de usuario de ADR-0008. Se aborda después de Windows y solo si el usuario lo pide.'
Decisiones: 'Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. Reutiliza la base común de [[Mouse pad en destinos Windows]]. La preparación con root de uinput necesita la aprobación del usuario y una ADR, porque se aparta de [[ADR-0008 Diseño del agente de resiliencia nivel 3]]. El 2026-10-21 el usuario decide hacer la vía del portal dentro de [[Escritorio remoto en destinos Linux Wayland]] (ADR-0031), que comparte su cliente D-Bus y el portal; los compositores wlroots la tienen por [[Escritorio remoto en destinos Linux wlroots]].'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: '1.5.0'
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-29T00:30:00+01:00
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

## Avance

2026-10-22 (rama `worktree-desktop-wayland`, solo en la canary): `--input` en
Wayland prueba el portal RemoteDesktop (sesión solo de puntero y teclado, con
su propio token) y, en GNOME con el acceso desatendido, la API de Mutter; los
wlroots van por su propia vía. Probado en el Plasma real (puntero relativo) y
en el GNOME de `tools/test-gnome` (texto con ñ). Sin «Permitir interacción
remota» en el diálogo de GNOME, `E_SCREEN_CONSENT`.

Limitación: el portal y Mutter solo teclean los caracteres que están en los
niveles normal y de mayúsculas de la distribución de teclado del destino; las
letras con tecla muerta (á, é) y las de AltGr (€) no llegan. Sigue en
[[Teclear acentos y AltGr en destinos Wayland con portal]].

El 2026-10-22 el usuario descarta el respaldo con uinput: el portal (KDE,
GNOME) y los protocolos wlroots cubren los escritorios Wayland habituales.

## Verificación

- **Prueba del usuario** (2026-10-28, canary 1.4.1-canary.45, KDE Plasma con
  Wayland desde el Pixel): puntero, clics, arrastre, scroll, teclado (también
  `áéíóú ü € @ ñ`) y un solo diálogo recordado, todo correcto.

## Cómo probarlo

**Dónde**: la canary del móvil (todavía no está en ninguna versión estable).
Destino: un KDE o GNOME con la sesión gráfica iniciada en Wayland
(`<destino-wayland>`), con alguien delante para el diálogo la primera vez.

**Preparación**: en la canary, una sesión de tipo «mouse pad» para ese
destino.

**Pasos**:

1. Abrir la sesión y aceptar el diálogo del portal en el destino (en GNOME,
   con «Permitir interacción remota» marcado).
2. Con los gestos del mouse pad: mover, clic izquierdo y derecho, arrastrar
   una ventana, scroll vertical y horizontal.
3. En un terminal del destino: escribir `echo ñ`, usar Tab, las flechas y
   Ctrl+C. Los acentos con tecla muerta (á) y AltGr (€) **no** llegan
   todavía ([[Teclear acentos y AltGr en destinos Wayland con portal]]).
4. Cerrar y volver a abrir la sesión: ya no sale el diálogo.

**Qué debe pasar**: todo lo anterior responde como un ratón y un teclado
físicos, con un solo diálogo.

**Si falla**: qué gesto o tecla, escritorio y versión, y versión de la
canary.

## Resultado

El mouse pad controla KDE y GNOME con Wayland por el portal RemoteDesktop (y,
en GNOME con acceso desatendido, por la API de Mutter), con el permiso
recordado. Los wlroots van por sus propios protocolos. Sin uinput. Llega a la
estable en la 1.5.0 ([[Pasar a la estable el escritorio remoto y la instalación de la clave]]).
