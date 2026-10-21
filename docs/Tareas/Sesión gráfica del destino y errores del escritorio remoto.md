---
Nombre: 'Sesión gráfica del destino y errores del escritorio remoto'
Estado: 'Pendiente'
Resumen: 'Base común para llevar el escritorio remoto a Windows y a cualquier Linux (ADR-0031): buscar la sesión gráfica del usuario en logind (/run/systemd/sessions) en lugar de solo en /proc, para distinguir X11, Wayland (KDE, GNOME, wlroots), pantalla de inicio, sesión remota e inactiva; el catálogo de errores nuevo (E_DESKTOP_INACTIVE, E_SCREEN_CONSENT, E_SCREEN_CAPTURE) en el agente y en la app; el estado blocked en el canal de pantalla; y el soporte en screen.Serve para varios monitores compuestos y para pasar POINTER_TO a coordenadas lógicas (HiDPI). Las tareas de Wayland, wlroots y Windows se apoyan en ella.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-21T18:55:00+02:00
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
- En el PC de desarrollo (KDE Wayland), una build canary de desarrollo
  responde `E_SCREEN_UNSUPPORTED` con «KDE Plasma (Wayland)» en el detalle
  mientras no exista la vía del portal; X11 sigue igual contra `tools/test-x11`.
- Tests de Go y de Kotlin de los códigos nuevos y del estado `blocked`.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
