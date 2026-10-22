---
Nombre: 'Sesión gráfica del destino y errores del escritorio remoto'
Estado: 'Hecha'
Resumen: 'Base común para llevar el escritorio remoto a Windows y a cualquier Linux (ADR-0031): buscar la sesión gráfica del usuario en logind (/run/systemd/sessions) en lugar de solo en /proc, para distinguir X11, Wayland (KDE, GNOME, wlroots), pantalla de inicio, sesión remota e inactiva; el catálogo de errores nuevo (E_DESKTOP_INACTIVE, E_SCREEN_CONSENT, E_SCREEN_CAPTURE) en el agente y en la app; el estado blocked en el canal de pantalla; y el soporte en screen.Serve para varios monitores compuestos y para pasar POINTER_TO a coordenadas lógicas (HiDPI). Las tareas de Wayland, wlroots y Windows se apoyan en ella.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21). Hecha el 2026-10-21: paquete internal/graphical, reparto por vía en screen_wayland.go (screen_portal.go y screen_wlroots.go son de las tareas de Wayland y wlroots), estado blocked como INPUT_READY en el canal de pantalla y screen.Composite para varios monitores con Locate para POINTER_TO.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-22T20:30:00+02:00
---

# Sesión gráfica del destino y errores del escritorio remoto

Parte de [[Escritorio remoto a través del agente]].

## Objetivo

Dejar preparado lo que comparten [[Escritorio remoto en destinos Linux Wayland]],
[[Escritorio remoto en destinos Linux wlroots]] y
[[Escritorio remoto en destinos Windows]], para que se puedan hacer en
paralelo sin pisarse, y que cada caso no cubierto dé ya un error concreto.

## Plan

1. **Sesión gráfica** (`internal/x11/session*.go` pasa a un paquete propio de
   sesión): logind primero (`TYPE`, `CLASS`, `SEAT`, `REMOTE`, `ACTIVE`,
   `DESKTOP`, `UID`, líder y su entorno), `/proc` de respaldo. Devuelve el tipo
   (X11, Wayland), el escritorio (KDE, GNOME, wlroots u otro), `DISPLAY`,
   `WAYLAND_DISPLAY`, `XAUTHORITY` y `DBUS_SESSION_BUS_ADDRESS`.
2. **Reparto por vía** en `--screen` y `--input`: X11, portal, wlroots y
   Windows, cada una en sus ficheros, para que cada tarea añada la suya.
3. **Errores**: los códigos de ADR-0031 §6 en `errors.go` y en
   `AgentDiagnostics.kt`, con sus textos y tests.
4. **Estado `blocked`** en `--screen` (agente y app: imagen atenuada con el
   motivo).
5. **Varios monitores y HiDPI** en `screen.Serve`: un capturador compuesto de
   varias fuentes y una conversión de `POINTER_TO` por fuente.

## Criterios de finalización

- Tests de la búsqueda de sesión con ficheros de logind y entornos de
  ejemplo: X11 local, Wayland KDE, GNOME y sway, xrdp, pantalla de inicio,
  otro usuario en el asiento y sin systemd.
- En un escritorio KDE Wayland, una build canary de desarrollo
  responde `E_SCREEN_UNSUPPORTED` con «KDE (Wayland)» en el detalle
  mientras no exista la vía del portal; X11 sigue igual contra `tools/test-x11`.
- Tests de Go y de Kotlin de los códigos nuevos y del estado `blocked`.

## Verificación

2026-10-21:

- **Agente**: `go test -race ./...` en verde; `go vet` también con `-tags canary`
  y para Windows, macOS y FreeBSD.
  - `internal/graphical`: ficheros de logind y entornos de ejemplo (KDE
    Wayland en seat0, GNOME X11 local frente a xrdp, sesión cambiada con otro
    usuario en el asiento, pantalla de inicio, otro usuario, sin logind con
    Xvfb a mano, XWayland, Hyprland sin XWayland, el `DISPLAY` propio).
  - `internal/screen`: `Composite` con dos monitores en posiciones negativas
    (imagen, cursor que cambia de monitor, `Locate`) y `Serve` que deja de
    capturar mientras está bloqueado, avisa con `INPUT_READY` y sigue al
    desbloquear.
- **App** (`:shared:desktopTest`, 528 tests en verde): `ScreenTransportTest`
  (bloqueado desde el principio, desbloqueo y acuse; `E_DESKTOP_INACTIVE`
  explicado) y `AgentDiagnosticsTest` (los cinco códigos del escritorio con su
  texto y el detalle).
- **X11**: `ScreenTransportIntegrationTest` por SSH contra `tools/test-x11` con
  un agente canary de esta rama (sin logind dentro del contenedor: la búsqueda
  por `/proc`).
- **KDE Plasma en Wayland, sesión de escritorio** (agente de desarrollo con
  su propio directorio de estado): `--screen` responde `E_SCREEN_UNSUPPORTED
  KDE (Wayland) is not supported yet` y `--input`, `E_INPUT_UNSUPPORTED the
  mouse pad does not support KDE (Wayland) yet`.

## Resultado

- `internal/graphical` sustituye a la búsqueda de sesión de `internal/x11`:
  logind primero, `/proc` de respaldo; tipo, escritorio y familia (KDE,
  GNOME, wlroots), variables de la sesión y errores `ErrNoSession` /
  `ErrInactive` con su motivo.
- `--screen` reparte por vía: X11 en `screen_linux.go`; Wayland prueba
  `openPortalScreen` y `openWlrootsScreen` (en ese orden, o al revés en la
  familia wlroots) y, si ninguna aplica (`errNoWay`), `E_SCREEN_UNSUPPORTED`
  con el escritorio.
- Códigos nuevos `E_DESKTOP_INACTIVE`, `E_SCREEN_CONSENT` y `E_SCREEN_CAPTURE`
  en `errors.go` y `AgentDiagnostics.kt`, con texto y detalle.
- `screen.Blocker`: un capturador bloqueado no se captura y la app lo enseña
  como el mouse pad, con la última imagen atenuada (también si está bloqueado
  desde el principio).
- `screen.Composite` y `Part`: varios monitores en una imagen, con `Locate`
  para llevar `POINTER_TO` al monitor y sus coordenadas.
