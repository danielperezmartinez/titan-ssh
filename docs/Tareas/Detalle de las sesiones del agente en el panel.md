---
Nombre: 'Detalle de las sesiones del agente en el panel'
Estado: 'En curso'
Resumen: 'Petición del usuario (2026-09-30): que el panel del agente deje claro qué sesiones siguen vivas y se pueden recuperar, con datos que ayuden a reconocerlas. Por ejemplo, desde cuándo están activas y la fecha y hora en que empezaron. Hoy el panel solo da el estado, el último uso y la memoria. Ideas en dos grupos: las que salen de datos que el agente ya envía (inicio, tiempo activa, desde cuándo sin cliente, tamaño del historial, número de clientes) y las que piden cambiar el agente (shell, proceso en primer plano, directorio actual, tamaño del PTY, última salida, vista previa de la pantalla). El usuario aprueba todas las ideas (2026-09-30), y todo va en el panel del agente, nunca en la franja de encima del terminal.'
Decisiones: 'Decisión del usuario (2026-09-30): se hacen todas las ideas de la lista, y solo en el panel del agente (la pantalla secundaria "[<] Agente · destino"). La franja de estado del terminal no recibe nada, según [[Franja de estado del terminal solo para el estado y los scripts]]. Cómo se reparte el detalle dentro del panel (fila plegada o desplegada) se propone al implementarlo. Amplía el panel de [[Estado y control del agente en la interfaz]], que se hizo en [[Transparencia y control del agente en el destino]]. Si cambia lo que se ve en el panel, se decide con el usuario y se recoge en esa decisión visual o en una nueva que la reemplace.'
Bloqueada: []
Fecha de creación: 2026-09-30T21:00:00+02:00
Última modificación: 2026-09-30T22:00:00+02:00
---

# Detalle de las sesiones del agente en el panel

## Objetivo

Que el usuario vea en el panel del agente qué sesiones siguen vivas en el
destino (recuperables al abrirlas), cuáles son antiguas y qué hay dentro de
cada una, sin tener que abrirlas. Surgió al ver en una pestaña restos del
centinela que parecían venir de una sesión antigua (ver
[[Sin rastro de la automatización en destinos Windows]]).

## Situación de partida

- `titan-agent --status --json` ya envía por sesión `createdMs`,
  `lastUsedMs`, `detachedMs`, `clients`, `closed`, `bufferBytes` y
  `memoryBytes` (`AgentSessionReport`, `control.go`).
- El panel (`AgentPanel.kt`) muestra por sesión un solo estado
  (`conectada ahora`, `en segundo plano · último uso hace X`,
  `sin conectar desde hace X`, `huérfana`) y la memoria. No usa `createdMs`,
  `detachedMs`, `bufferBytes` ni el número de clientes.

## Ideas

### Con los datos que ya envía el agente (solo app)

- **Inicio de la sesión**: fecha y hora (`creada el 27/09 16:10`) y tiempo
  activa (`activa desde hace 3 días`).
- **Desde cuándo está sin cliente**, con fecha y hora, además del "hace X".
- **Clientes conectados**: `en 2 pestañas o dispositivos` cuando hay más de uno.
- **Historial guardado** (`bufferBytes`): cuánto se reproducirá al reengancharse.
- **Etiqueta "recuperable"** clara frente a una sesión cuya shell ya terminó
  (`closed`), que hoy se oculta.
- Una fila desplegada con el detalle completo, para que la fila plegada siga
  corta.

### Con cambios en el agente

- **Shell** de la sesión (`cmd.exe`, `pwsh`, `bash`…) y su PID.
- **Proceso en primer plano**: qué está corriendo ahora (`claude`,
  `npm run dev`, `vim`…). Es lo que más ayuda a reconocer una sesión. En
  POSIX sale del grupo de primer plano del PTY; en Windows, del árbol de
  procesos bajo ConPTY.
- **Directorio actual** de la shell: fácil en Linux (`/proc/<pid>/cwd`) y
  difícil en Windows.
- **Última salida**: cuándo escribió algo por última vez, para saber si hay
  algo en marcha aunque nadie la mire.
- **Vista previa**: las últimas líneas de la pantalla o el título de la
  ventana (OSC 0/2).
- **Tamaño del PTY** (columnas × filas).
- **Versión del agente que creó la sesión**, útil tras actualizar.
- **CPU** de la sesión, junto a la memoria.

## Criterios de finalización

- Todas las ideas de la lista se ven en el panel del agente, y ninguna en la
  franja de estado del terminal. El reparto entre fila plegada y desplegada
  lo aprueba el usuario.
- El agente y `AgentSessionReport` reportan los datos nuevos, si los hay, con
  el esquema actualizado en los dos lados.
- Probado en el emulador contra el contenedor de pruebas y contra el sshd de
  Windows, con sesiones creadas en momentos distintos.

## Implementación (2026-09-30)

- **Agente** (`agent/`):
  - `procmem` lee también el nombre y el tiempo de CPU de cada proceso
    (`/proc/<pid>/stat`, Toolhelp + `GetProcessTimes`, `ps -o time= -o comm=`),
    con `TreeCPU`, `Name`, `Newest` y `Cwd`. `Cwd` solo funciona en Linux.
  - `session` guarda el tamaño del PTY, la hora de la última salida y el
    título de ventana (`titleScanner`, OSC 0/2 aunque llegue partido entre
    lecturas). También añade `Registry.Tail`, y los PTY exponen `Shell()` y,
    en POSIX, `Foreground()` (`TIOCGPGRP`).
  - `--status` añade campos opcionales por sesión: `cpuPercent` (dos
    instantáneas a 250 ms), `shell`, `shellPid`, `foreground`,
    `foregroundPid`, `cwd`, `cols`, `rows`, `lastOutputMs` y `title`. El
    esquema sigue en 1. En Windows, el programa en marcha es el descendiente
    más reciente de la shell (sin `conhost`/`OpenConsole`), porque ConPTY no
    tiene grupo de primer plano.
  - `--preview <id> [--json]`, orden nueva: los últimos 16 KiB de la salida y
    el tamaño del PTY.
  - La conexión de control amplía su plazo tras leer la petición, porque la
    respuesta incluye la muestra de CPU.
- **App**: `AgentSessionReport` con los campos nuevos, `AgentPreview` (lo
  reproduce en un `TerminalEmulator` desechable), `AgentControl.preview`,
  `AgentManager.refreshWithPreviews` (vistas previas solo en memoria),
  `AgentInsights.sessionDetails` y `formatLocalDateTime` (`expect`/`actual`
  con `java.time`). Panel según
  [[Detalle de las sesiones en el panel del agente]] (`Propuesta` hasta que el
  usuario lo vea).
- **Descartado**: la *versión del agente que creó la sesión*. Todas las
  sesiones de un daemon las crea ese mismo daemon, y actualizar el agente
  cierra sus sesiones, así que coincidiría siempre con la versión de la
  cabecera del panel.

## Verificación

- Go en Windows (`go test ./...`) y en Linux con `-race` (Docker
  `golang:1.27`), incluido un test con PTY real que sigue el grupo de primer
  plano (`sleep`). Compila para macOS, FreeBSD, ARM y RISC-V, y los tests del
  parser de `ps` pasaron en una copia desechable, porque solo corren en
  macOS/FreeBSD.
- ConPTY real en Windows (`TestSessionDetailsOnARealConPty`): `cmd.exe`,
  100×30, `PING.EXE` en marcha, CPU, memoria y el título que pone ConPTY.
- `:shared:desktopTest`: 245 tests, 0 fallos, con `AgentSessionDetailsTest`
  (13) nuevo.
- `AgentControlIntegrationTest` contra el contenedor de pruebas: shell
  `/bin/bash`, 80×24, `sleep` en marcha con su directorio y CPU, y la vista
  previa muestra `~$ sleep 60`.
- Contra el Windows del PC de desarrollo no se detiene ni se actualiza el
  agente, porque tiene sesiones vivas del usuario. Lo nuevo de Windows se
  cubre con el test de ConPTY real.
- **Emulador `Pixel_9_Pro_XL`** (build de debug) contra el contenedor de
  pruebas, con dos sesiones de nivel 3 creadas en momentos distintos (`top`
  en `~/proyecto` y otra en el prompt):
  - La fila plegada antepone `top`.
  - Desplegada, se ven la fecha de creación, desde cuándo está sin conectar,
    el programa con su PID, la shell con su tamaño (57×24 y 57×42), el
    directorio, la última salida, la CPU, la memoria, el historial y la vista
    previa con la pantalla de `top`.
  - Salió que el directorio era el del programa (`/proc`, porque busybox
    `top` se mueve allí). Se cambió al de la shell.
  - Contra el Windows del PC, solo consultando: su agente es de la beta.7 y
    la app avisa de "otra versión". La sesión del Pixel sale como huérfana
    con los datos que da ese agente (fechas, memoria, historial), sin vista
    previa ni huecos. Por esa sesión, una huérfana ya no dice que al abrirla
    se vuelve a la terminal: aquí no hay sesión guardada para abrirla.
  - Capturas en la carpeta temporal de capturas del emulador
    (`detalle-sesiones`).
- **Pendiente**: que el usuario apruebe el reparto entre fila plegada y
  desplegada. Hasta entonces, la decisión visual sigue en `Propuesta` y la
  tarea en `En curso`.

Hallazgo aparte, sin tocar: la ruta inicial `~/proyecto` de la sesión de
pruebas falla (`cd -- '~/proyecto'`: *No such file or directory*).
`ShellSyntax.cd` pone la ruta entre comillas y el `~` no se expande, y eso es
a propósito según su comentario. Pero una ruta con `~` es lo natural en un
destino POSIX. Queda para decidir con el usuario.

## Resultado
