---
Nombre: 'Abrir el explorador SFTP desde una sesión abierta'
Estado: 'Hecha'
Resumen: 'Petición del usuario (2026-10-14), para más adelante. Además del tipo de sesión SFTP, poder abrir el explorador de archivos desde una pestaña de terminal ya conectada, sobre su misma conexión SSH, sin crear una sesión guardada de tipo SFTP. El usuario quiere que esta acción se abra desde el menú de acciones de la pestaña que nacerá con [[Renombrar las pestañas y las sesiones activas]].'
Decisiones: 'Sale de [[Sesión SFTP para navegar y descargar archivos]] y de [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]], que solo cubren el tipo de sesión. Decidido con el usuario el 2026-10-14, junto con [[Renombrar las pestañas y las sesiones activas]]: acción del menú de la pestaña (pulsación larga), el explorador se abre en una pestaña nueva y arranca en la carpeta actual de la terminal cuando se puede saber con certeza (agente en Linux, tmux u OSC 7 del shell); si no, en la de inicio con un aviso. Comparte la conexión SSH de la terminal; si esa pestaña se cierra, el explorador sigue con su propia conexión.'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-14T13:30:00+02:00
---

# Abrir el explorador SFTP desde una sesión abierta

## Objetivo

Desde una pestaña de terminal conectada, abrir el explorador SFTP del mismo
destino reutilizando su conexión, sin tener que crear ni lanzar una sesión
guardada de tipo SFTP.

## Decisiones (2026-10-14)

- **Punto de entrada**: "Abrir explorador de archivos" en el menú de la
  pestaña de [[Renombrar las pestañas y las sesiones activas]] (pulsación
  larga; clic derecho en escritorio).
- **Dónde se abre**: en una pestaña nueva, con la misma vista que una sesión
  SFTP, para alternar entre terminal y explorador.
- **Conexión** (decisión técnica del agente): abre un canal SFTP sobre la
  conexión SSH de la terminal, así que no vuelve a autenticar ni a pasar por
  el bastión. Cuando la terminal reconecta, el explorador espera y sigue en
  la conexión nueva, como una sesión SFTP tras un corte. Si se cierra la
  pestaña de la terminal, el explorador no se cierra: pasa a usar su propia
  conexión al mismo destino.
- **Carpeta inicial**: la actual de la terminal cuando se puede saber con
  certeza; si no, la de inicio con un aviso que lo dice. Se explicó al
  usuario por qué no siempre se puede saber:
  - Nivel 3 en Linux: el agente lee la carpeta del shell (`/proc/<pid>/cwd`),
    que ya da su `--status`.
  - Nivel 2 con tmux: `tmux display-message -p '#{pane_current_path}'`.
  - Cualquier nivel: la secuencia OSC 7 si el shell la envía (fish, o
    bash/zsh con `vte.sh`). La app no la activa en el shell del usuario.
  - Sin forma fiable: nivel 1 sin OSC 7, screen, PowerShell en Windows (no
    cambia la carpeta del proceso), o si dentro de la terminal se entró en
    otra máquina o con otro usuario (`ssh`, `docker exec`, `sudo -i`).
  - Si la ruta obtenida no existe o no se puede abrir, también se usa la de
    inicio.

## Criterios de finalización

- Desde una pestaña de terminal se abre el explorador del mismo destino.
- Probado en el emulador y en escritorio.

## Verificación

Hecho el 2026-10-14 en la rama `worktree-tab-menu`, junto con
[[Renombrar las pestañas y las sesiones activas]]:

- `SessionTabExplorerTest`: el explorador abre su canal sobre la conexión de
  la terminal sin conectar por su cuenta, la sigue cuando la terminal
  reconecta, cerrarlo no cierra la conexión de la terminal y, cerrada la
  terminal (antes o después de conectar), conecta por su cuenta. El
  `SessionManager` lo abre activo, lo llama `<pestaña> · archivos`, no lo
  cuenta como instancia y, sin carpeta conocida, muestra el aviso.
  `Osc7Test` (rutas, escapes, BEL y ST, troceado, otros OSC y DCS) y
  `SftpBrowserTest` (avisos de la carpeta inicial).
- Emulador `Pixel_9_Pro_XL` contra el contenedor de pruebas:
  - Nivel 3: `cd /etc` en la terminal y "Abrir explorador de archivos" →
    pestaña `logs · archivos` en `/etc` (la carpeta que da el agente).
  - Nivel 2 (tmux): `cd /usr/share` → el explorador abre en `/usr/share`.
- El caso sin carpeta conocida (nivel 1 sin OSC 7, screen o Windows) queda
  cubierto por el test del `SessionManager`, no en un destino real.
- El usuario lo probó el 2026-10-14 y dio la tarea por finalizada.

## Resultado

- "Abrir explorador de archivos" en el menú de una pestaña de terminal abre
  una pestaña `<pestaña> · archivos` con la vista SFTP
  (`SessionManager.openExplorer`).
- Usa la conexión SSH de la terminal mientras esa pestaña viva y la sigue en
  sus reconexiones; si se cierra la terminal, conecta por su cuenta
  (`SessionTab.explorerOf`).
- Arranca en la carpeta actual de la terminal (`SessionTab.currentDirectory`:
  agente en Linux, tmux u OSC 7, que ahora lee el emulador) o en la de inicio
  con un aviso en la vista (`SftpListing.notice`).
