---
Nombre: 'Carpeta actual de la terminal en destinos Windows'
Estado: 'En curso'
Resumen: 'Petición del usuario (2026-10-15) tras probar en el Pixel: al abrir el explorador SFTP desde una pestaña de nivel 3 contra Windows sale el aviso de carpeta desconocida, porque el agente solo sabe la carpeta del shell en Linux. Que el agente de Windows la sepa también: en cmd.exe leyéndola de la memoria del proceso, y en PowerShell envolviendo su prompt en memoria para que la anote.'
Decisiones: 'Decidido con el usuario el 2026-10-15, después de explicarle qué supone tocar el prompt: ambas partes. En PowerShell no se toca el $PROFILE ni ningún fichero del usuario ni el aspecto del prompt; el agente arranca el shell de la sesión con el envoltorio ya puesto, solo en sesiones nuevas. Detalle técnico (decisión del agente): el envoltorio anota la carpeta en un fichero temporal del agente, no con una secuencia de escape, para no depender de lo que ConPTY deje pasar.'
Bloqueada: []
Fecha de creación: 2026-10-15T10:00:00+02:00
Última modificación: 2026-10-15T11:00:00+02:00
---

# Carpeta actual de la terminal en destinos Windows

## Objetivo

Que "Abrir explorador de archivos" desde una pestaña de nivel 3 contra un
destino Windows arranque en la carpeta actual de la terminal, como ya pasa en
Linux, en lugar de la de inicio con el aviso. Sale de
[[Abrir el explorador SFTP desde una sesión abierta]], que dejó Windows entre
los casos sin carpeta fiable, y de [[Detalle de las sesiones del agente en el panel]],
donde el directorio actual quedó solo para Linux.

## Decisiones (2026-10-15)

- **cmd.exe**: `cd` cambia la carpeta del proceso. El agente la lee de la
  memoria del proceso del shell (PEB, `CurrentDirectory`), que es del mismo
  usuario. Sin tocar nada del shell.
- **PowerShell** (`powershell.exe` y `pwsh.exe`): `Set-Location` no cambia la
  carpeta del proceso, así que desde fuera no se puede saber. El agente
  arranca el shell de la sesión con `-NoExit -EncodedCommand` y un envoltorio
  de la función `prompt` (después de cargar el perfil del usuario): llama al
  prompt que hubiera (también oh-my-posh, starship…) y anota la carpeta
  actual del sistema de archivos en un fichero temporal del agente.
  - No toca el `$PROFILE` ni ningún fichero del usuario, ni lo que muestra el
    prompt. Vive solo en la memoria de ese shell.
  - Solo en sesiones nuevas del agente; las ya abiertas siguen sin carpeta.
  - Si el usuario redefine `prompt` a mano, se pierde y se vuelve al aviso.
- **Shells anidados**: si dentro de la sesión se abrió otro shell (por
  ejemplo `powershell` dentro de cmd, o `ssh`), se usa el más interno cuando
  se puede saber (cmd) y, si no, no se da carpeta.
- Solo rutas con letra de unidad (`C:\…`); una carpeta de red o de otro
  proveedor de PowerShell (`HKLM:`) no se da.

## Criterios de finalización

- El agente de Windows informa la carpeta de una sesión en cmd y en
  PowerShell (5.1 y 7), también tras `cd` y con un shell anidado.
- Desde la app, el explorador abierto desde una pestaña de nivel 3 contra
  Windows arranca en esa carpeta.
- Probado contra el Windows del PC de desarrollo sin tocar su agente real.

## Verificación

2026-10-15, en la rama `worktree-windows-cwd`:

- Go en Windows (`go test ./...`) y en Linux con `-race` (Docker
  `golang:1.27`), todo en verde. Nuevos:
  - `TestWindowsShellDirFollowsTheInnermostShell` y
    `TestDriveDirKeepsOnlyFoldersOnADrive` (reglas de shell anidada y rutas).
  - `TestCwdReadsTheWorkingDirectory` (Windows): lee del PEB la carpeta del
    propio proceso y la de un cmd.exe arrancado en otra.
  - `TestPowerShellReportsItsFolder`: PowerShell 5.1 y 7 reales sobre
    ConPTY, el prompt sigue saliendo, `Set-Location` se refleja, en `HKCU:\`
    no hay carpeta y el fichero se borra al cerrar.
  - `TestSessionDirFollowsCmdOnARealConPty`: `cd /d` en cmd se refleja, con
    un `powershell` anidado no hay carpeta y al salir vuelve.
- A mano en PowerShell 5.1 y 7: el prompt envuelto sigue viendo el `$?` y el
  `$LASTEXITCODE` del último comando (lo que usan oh-my-posh o starship).
- `AgentControlIntegrationTest` contra el Windows del PC de desarrollo con la
  cuenta estándar de pruebas (no la del usuario ni su agente): `--status` da
  la carpeta de la sesión y el explorador la abre por SFTP (`/C:/Users/…`).
  Perfil de la cuenta limpio después.
- Pendiente: probarlo desde el Pixel contra el Windows del usuario.

## Resultado

- El agente de Windows informa la carpeta de cada sesión en `--status`
  (`Table.ShellDir`, `agent/internal/procmem`): la de cmd.exe leída del
  proceso (`procmem.Cwd`) y la de PowerShell anotada por su prompt
  (`conPty.Cwd`, `agent/internal/session/pty_windows.go`), siguiendo la shell
  más interna de la sesión.
- La app no cambia: el explorador ya usaba esa carpeta
  (`SessionTab.currentDirectory`, `SftpPaths.initial`).
