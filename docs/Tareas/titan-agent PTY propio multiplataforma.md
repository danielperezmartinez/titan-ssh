---
Nombre: 'titan-agent PTY propio multiplataforma'
Estado: 'Pendiente'
Resumen: 'Subtarea 1 de ADR-0009 (§4): sustituir creack/pty por un envoltorio de PTY propio y fino detrás del seam session.Pty existente, solo con la stdlib (syscall, os/exec) y golang.org/x/sys. Backends: Linux (/dev/ptmx + TIOCSPTLCK/TIOCGPTN), macOS (TIOCPTYGRANT/TIOCPTYUNLK/TIOCPTYGNAME), FreeBSD (posix_openpt), y Windows con ConPTY (CreatePseudoConsole + atributo PSEUDOCONSOLE + CreateProcess). Incluye la shell por defecto por sistema, tests de contrato comunes (eco, redimensionado, EOF al salir, cierre) y quitar creack/pty de go.mod. Verificación en Windows local y Linux en Docker; macOS y FreeBSD solo compilación cruzada.'
Decisiones: 'Implementa §4 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] (sin librerías de terceros; x/sys es del proyecto Go). Contexto común y entornos en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:05:00+02:00
---

# titan-agent: PTY propio multiplataforma

Parte de [[Nivel 3 portable a todos los destinos]] (leer primero su contexto
común). Implementa §4 de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]].

## Objetivo

Que `session.PtyFactory` cree un PTY real en Linux, macOS, FreeBSD y Windows sin
`creack/pty` ni otras dependencias de terceros.

## Punto de partida

- El seam ya existe en `agent/internal/session/session.go`:
  `Pty{Read, Write, Resize(cols, rows uint16), Close}` y
  `PtyFactory func(cols, rows uint16) (Pty, error)`. **No cambia**: `Session` y
  los tests con `fakePty` siguen igual.
- Hoy: `pty_unix.go` (`//go:build unix`, `creack/pty`, `$SHELL -il` o
  `/bin/sh`, `Close` = cerrar master + `Kill` + `Wait`) y `pty_other.go` (stub
  que devuelve error).

## Diseño propuesto

Ficheros por sistema en `internal/session` (o un paquete `internal/pty` si
queda más limpio): `pty_linux.go`, `pty_darwin.go`, `pty_freebsd.go`,
`pty_windows.go` y un `pty_other.go` que siga devolviendo error (con el
código `E_PTY` del contrato) para los sistemas no cubiertos.

### Unix (común)

- Abrir el master, obtener el nombre del esclavo, abrir el esclavo con
  `O_RDWR|O_NOCTTY`.
- Hijo con `exec.Cmd`: `Stdin/Stdout/Stderr` = esclavo y
  `SysProcAttr{Setsid: true, Setctty: true, Ctty: 0}` (`Ctty` es el número de
  descriptor **en el hijo**; 0 = stdin). Cerrar el esclavo en el padre tras
  `Start`.
- Tamaño inicial y `Resize`: `unix.IoctlSetWinsize(fd, unix.TIOCSWINSZ, &unix.Winsize{Row, Col})`.
- Shell: `$SHELL -il`, o `/bin/sh -il` si no hay `$SHELL` (como hoy).
- **Trampa conocida**: en Linux, cuando el esclavo se cierra (sale la shell),
  `Read` sobre el master devuelve `EIO`, no `EOF`. Hay que traducir `EIO` a
  `io.EOF`, porque `Session.pump` trata cualquier error como fin.
- `Close`: cerrar el master, `Kill` del hijo y `Wait` (como hoy).

### Linux

`os.OpenFile("/dev/ptmx", O_RDWR|O_NOCTTY|O_CLOEXEC)`; desbloquear con
`ioctl(TIOCSPTLCK, 0)`; número con `ioctl(TIOCGPTN)` → `/dev/pts/<n>`.
(Alternativa más robusta en kernel ≥ 4.13: `TIOCGPTPEER` para abrir el esclavo
sin ruta; opcional.)

### macOS

`/dev/ptmx`, después `ioctl(TIOCPTYGRANT)`, `ioctl(TIOCPTYUNLK)` y
`ioctl(TIOCPTYGNAME)` (buffer de 128 bytes con el nombre). Comprobar si esas
constantes están en `golang.org/x/sys/unix` para darwin; si no, definirlas.

### FreeBSD

`posix_openpt` es una llamada al sistema (`SYS_POSIX_OPENPT`); el nombre del
esclavo se obtiene por ioctl. El código de `creack/pty` (MIT) sirve como
**documentación** de las constantes y el orden de llamadas, no como dependencia.

### Windows (ConPTY)

Lo que funcionó en la sonda del experimento
([[Experimento supervivencia de procesos en Win32-OpenSSH]]):

1. `windows.CreatePipe` ×2 → (`inR`, `inW`) y (`outR`, `outW`).
2. `windows.CreatePseudoConsole(windows.Coord{X: cols, Y: rows}, inR, outW, 0, &hpc)`.
3. `attr, _ := windows.NewProcThreadAttributeList(1)`;
   `attr.Update(0x00020016 /* PSEUDOCONSOLE */, unsafe.Pointer(hpc), unsafe.Sizeof(hpc))`.
   Se pasa el **valor** del handle, no un puntero a él. `go vet` avisa de
   "possible misuse of unsafe.Pointer": esperado; documentarlo con un comentario.
4. `StartupInfoEx` con `Cb = unsafe.Sizeof(si)`,
   `ProcThreadAttributeList = attr.List()` y **`Flags = STARTF_USESTDHANDLES`**
   (con handles a cero). Sin esto, si el agente tiene stdio redirigido (lo
   tiene: el canal SSH), el hijo hereda esos handles en vez del ConPTY.
5. `windows.CreateProcess(nil, cmdline, nil, nil, false, EXTENDED_STARTUPINFO_PRESENT, nil, nil, &si.StartupInfo, &pi)`
   (`os/exec` no admite el atributo).
6. Cerrar `inR` y `outW` en el padre tras crear el proceso.

Trampas:
- **Leer `outR` siempre, en una goroutine**. `ClosePseudoConsole` puede
  bloquearse si nadie vacía la salida.
- **ConPTY no da EOF cuando sale el hijo**: la tubería de salida sigue abierta
  hasta `ClosePseudoConsole`. Hay que esperar al proceso
  (`WaitForSingleObject(pi.Process)`) y entonces cerrar el ConPTY para que
  `Read` devuelva EOF, que es lo que `Session.pump` espera.
- Orden de cierre: `ClosePseudoConsole`, después terminar/esperar el proceso y
  cerrar las tuberías.
- La salida empieza con secuencias VT de inicialización (en la sonda,
  `echo CONPTY_OK & ver` dio 107 bytes): es normal y el emulador del cliente
  las entiende.
- `Resize`: `windows.ResizePseudoConsole(hpc, Coord{cols, rows})`.
- Versión mínima: si `CreatePseudoConsole` no existe
  (`LazyProc.Find()` falla) → error `E_NO_CONPTY`.

Shell en Windows: la de OpenSSH (`HKLM\SOFTWARE\OpenSSH\DefaultShell`, con
`golang.org/x/sys/windows/registry`) o, si no está, `%COMSPEC%`
(`cmd.exe`). En este PC la clave `HKLM\SOFTWARE\OpenSSH` solo tiene `Agent`, así
que la shell es `cmd.exe`.

## Tests de contrato

Un mismo conjunto de tests contra el `NewPty` real de cada sistema (con el
build tag que corresponda), sin tocar los de `fakePty`:
- **Eco**: escribir un comando que imprime un marcador y leerlo.
- **Tamaño**: arrancar con un tamaño, redimensionar y comprobar que la shell lo
  ve (Unix: `stty size`; Windows: `powershell -c $Host.UI.RawUI.WindowSize` o
  `mode con`).
- **EOF**: salir de la shell (`exit`) → `Read` devuelve `io.EOF` (no `EIO` ni
  bloqueo).
- **Close**: `Close` con la shell viva termina el hijo y no deja goroutines
  (valorar `goleak`, que es dependencia de test de terceros: si se usa, solo en
  tests y anotado).

## Criterios de finalización

- Backends Linux, macOS, FreeBSD y Windows implementados; `creack/pty` fuera de
  `go.mod`/`go.sum`; la única dependencia es `golang.org/x/sys`.
- Tests de contrato verdes en **Windows local** y **Linux (Docker, con
  `-race`)**; `GOOS=darwin` y `GOOS=freebsd` `go vet` limpios (ejecución no
  verificada: anotarlo).
- Los tests existentes (`protocol`, `buffer`, `session`, `cmd/titan-agent`)
  siguen verdes.
- `agent/README.md` actualizado (estructura de ficheros y dependencia).
