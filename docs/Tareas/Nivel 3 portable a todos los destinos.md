---
Nombre: 'Nivel 3 portable a todos los destinos'
Estado: 'Hecha'
Resumen: 'Tarea paraguas para implementar ADR-0009, completada el 2026-09-27 (las seis subtareas, publicadas en v0.1.0-beta.3): que el agente titan-agent (nivel 3) funcione en Windows, macOS, BSD y cualquier Linux, y degrade con diagnóstico donde no pueda. Reúne el orden de las subtareas, el contexto común (estado del código actual, entornos de verificación, contrato de errores, hallazgos de la investigación y del experimento de Windows) para poder empezar en una sesión limpia sin repetir la investigación. Subtareas: PTY propio multiplataforma; instancia única y directorio de estado; punto de encuentro TCP loopback con token; daemon en Windows; instalación del agente en destinos Windows y multi-SO; diagnóstico cuando el nivel 3 no está disponible.'
Decisiones: 'Implementa [[ADR-0009 Agente de nivel 3 portable a todos los destinos]], que sustituye en parte a [[ADR-0008 Diseño del agente de resiliencia nivel 3]], y el empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]. El desacople en Windows se decidió con [[Experimento supervivencia de procesos en Win32-OpenSSH]]. Continúa [[Resiliencia nivel 3 agente propio en el destino]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-27T15:45:00+02:00
---

# Nivel 3 portable a todos los destinos

## Objetivo

Implementar [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y el
empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]
(`Aceptada` el 2026-09-27: el resto de destinos se descarga del GitHub Release). **Leer
la ADR-0009 antes de empezar cualquier subtarea**: es la fuente de verdad del diseño.
Esta nota solo añade orden, contexto de implementación y lo aprendido al
investigar.

## Subtareas y orden

| # | Subtarea | Depende de |
|---|---|---|
| 1 | [[titan-agent PTY propio multiplataforma]] (hecha el 2026-09-27) | — |
| 2 | [[titan-agent instancia única y directorio de estado]] (hecha el 2026-09-27) | — |
| 3 | [[titan-agent punto de encuentro TCP loopback con token]] (hecha el 2026-09-27) | 2 |
| 4 | [[titan-agent daemon en Windows]] (hecha el 2026-09-27) | 1, 3 |
| 5 | [[Instalación del agente en destinos Windows y multi-SO]] (hecha el 2026-09-27; publicada en `v0.1.0-beta.3`) | 4 (solo la prueba de punta a punta en Windows) |
| 6 | [[Diagnóstico cuando el nivel 3 no está disponible]] (hecha el 2026-09-27) | 3, 4, 5 |

1 y 2 son independientes y pueden hacerse en cualquier orden. 5 puede avanzar
en paralelo a 4 (detección, SFTP, rutas), pero su verificación en Windows
necesita 4.

## Estado del código de partida (2026-09-23)

- **Punto de partida en git**: el estado del agente y del cliente del nivel 3
  descrito aquí quedó en `main` en los commits del 2026-09-23 (agente de nivel 3
  + integración en el cliente, y documentación de ADR-0009/ADR-0010). Comparar
  contra ellos. Las skills instaladas están versionadas en `.agents/skills/` con
  `skills-lock.json`; `.claude/skills/` son *junctions* locales (rutas
  absolutas) ignoradas por git, que se recrean en cada equipo.
- Agente Go en `agent/` (módulo `github.com/danigar/titan-ssh/agent`, `go 1.22`,
  única dependencia `github.com/creack/pty v1.1.24`; desde la subtarea 1,
  hecha el 2026-09-27, la única dependencia es `golang.org/x/sys` y `go.mod`
  pide `go 1.26`):
  - `cmd/titan-agent/main.go`: modos front (por defecto) y `--daemon`; flags
    `--socket`, `--buffer-bytes`, `--session` (informativo), `--version`;
    `var version`, estampada por Gradle con `-ldflags -X main.version` (versión
    única de la app desde 2026-09-24; `0.0.0-dev` en un `go build` suelto).
  - `cmd/titan-agent/daemon.go`: `runDaemon` (socket Unix) y `serveConn`. **Se
    conserva** `serveConn` con el arreglo de la fuga previa al HELLO
    (`helloTimeout`, la trama previa al HELLO corta la conexión, espera a su
    lector); ver [[titan-agent fuga previa al HELLO y permisos del socket]].
  - `cmd/titan-agent/front.go`: `runFront` + `dialOrSpawn` (sondea el socket
    5 s cada 50 ms).
  - `cmd/titan-agent/sock_unix.go`: `ensureSocketDir` (directorio real, del uid,
    sin acceso de grupo u otros) y `listenPrivate` (umask 0177 durante `Listen`).
    `ensureSocketDir` pasa a proteger el directorio de estado (subtarea 2);
    `listenPrivate` desaparece (subtarea 3).
  - `cmd/titan-agent/detach_{unix,other}.go`: `setsid` / no-op.
  - **Desde las subtareas 2 y 3** (hechas el 2026-09-27) ya no hay socket
    Unix: `sock_unix.go` pasó a `statedir_*.go` (`ensureStateDir`); hay
    candado (`lock*.go`), fichero de estado (`state.go`) y preámbulo con token
    (`rendezvous.go`); `front.go` tiene `dialOrSpawn` (10 s), `spawnDaemon` y
    `stopDaemon` (`--stop`); `main.go` cambia `--socket` por `--state-dir`, y
    los códigos de error están en `errors.go`. Detalle en el **Resultado** de
    cada subtarea.
  - `internal/protocol`, `internal/buffer`: **no cambian**.
  - `internal/session`: `Pty` (seam `Read/Write/Resize/Close`), `PtyFactory`,
    `Session`, `Registry`. `pty_unix.go` usa `creack/pty` (sustituir en la
    subtarea 1); `pty_other.go` es un stub.
- Cliente Kotlin del nivel 3: `AgentProtocol.kt`, `AgentTransport.kt`,
  `AgentInstall.kt` (commonMain); `AgentInstaller.kt`,
  `AgentDeployerFactory.jvmShared.kt` (jvmSharedMain); build de binarios en
  `shared/build.gradle.kts` (`buildAgentBinaries`, `agentTargets`). Detalle en la
  subtarea 5.
- `gofmt -l` ya marcaba `cmd/titan-agent/main.go` y
  `internal/session/session_test.go` antes de estos cambios: no es un fallo
  introducido; se puede corregir de paso.

## Entornos de verificación

- **Windows (local)**: `go test ./...` directamente. `-race` **no** funciona
  (exige cgo).
- **Linux**: contenedor Docker `golang:1.27` (Docker Desktop está instalado; la
  imagen ya está descargada). Desde el Bash tool:

  ```
  MSYS_NO_PATHCONV=1 docker run --rm -v "P:/Apps/titan-ssh-c/agent:/src:ro" -w /src \
    -e GOCACHE=/tmp/gocache -e GOMODCACHE=/tmp/gomod -e GOFLAGS=-mod=mod \
    --user 1000:1000 golang:1.27 go test -race -count=1 ./...
  ```

  Para pruebas entre usuarios (permisos), ejecutar como root y crear usuarios con
  `useradd` + `su`, como en
  [[titan-agent fuga previa al HELLO y permisos del socket]].
- **Linux real**: host de pruebas `<host-de-pruebas>` (Tailscale, usuario
  `<usuario>`, clave `~/.ssh/<clave-de-pruebas>`). El 2026-09-23 rechazaba el puerto
  22; comprobar antes de contar con él.
- **Linux de punta a punta sin el host de pruebas** (usado el 2026-09-27 en las
  subtareas 2 y 3): un sshd temporal en un contenedor `golang:1.27`
  (`apt-get install openssh-server`, usuario de prueba, clave desechable
  generada fuera del repositorio, puerto publicado solo en `127.0.0.1:2222`).
  Se compila el agente con `GOOS=linux GOARCH=amd64 CGO_ENABLED=0` y se lanzan
  los tests Kotlin `AgentTransportIntegrationTest` y
  `AgentInstallerIntegrationTest` con `-PtitanSshTestHost=127.0.0.1
  -PtitanSshTestPort=2222 -PtitanSshTestUser=... -PtitanSshTestKey=...
  -PtitanAgentBin=... --rerun-tasks`. Al terminar se borran el contenedor y la
  clave.
- **`go vet ./...` en Windows** marca `internal/session/pty_windows.go`
  ("possible misuse of unsafe.Pointer"). Es esperado y está comentado en el
  código: el atributo de ConPTY recibe el valor de `HPCON`, no un puntero.
  Para comprobar el resto, `go vet ./cmd/...`.
- **Windows como destino SSH**: en las subtareas 4 y 5 (2026-09-27) se usó la
  opción ya probada, con ayuda del usuario (ver [[ADR-0012 Entorno de pruebas automático multiplataforma]]).
  Win32-OpenSSH ejecuta los `exec` como `cmd.exe /c "<comando>"`. Datos de esa opción: el
  `sshd` local (`OpenSSH_for_Windows_10.0p2`, servicio automático,
  `C:\Program Files\OpenSSH` desde el 2026-09-27). Solo clave
  (`PasswordAuthentication no`), claves en `C:\Users\<u>\.ssh\authorized_keys`
  (también para administradores: no hay bloque `Match Group administrators`),
  shell por defecto `cmd.exe`. Para probar **sin administrador** hace falta un
  usuario estándar; desde el 2026-09-27 se **conserva** una cuenta de pruebas
  (ver [[titan-agent daemon en Windows]]). Los pasos para crear otra (crear usuario, `runas` para crear el
  perfil y autorizar la clave, limpieza) están en
  [[Experimento supervivencia de procesos en Win32-OpenSSH]]. **Al terminar,
  reiniciar `sshd` o el PC antes de borrar el perfil**: sshd lo deja cargado y
  el borrado falla con "archivo en uso".
- **systemd sin systemd** (subtarea 6, 2026-09-27): el aviso de
  `KillUserProcesses` se prueba en el sshd de Docker con un
  `/etc/systemd/logind.conf` con `KillUserProcesses=yes` y un `loginctl` falso en
  `/usr/local/bin` (responde `Linger=no` hasta que se ejecuta `enable-linger`).
  Sin `busctl` la sonda lee `logind.conf`; la rama de `busctl` se comprobó a mano
  en el host de pruebas, que tiene systemd de verdad.
- **Otras arquitecturas de Linux**: Docker Desktop emula con QEMU
  (`--platform linux/arm/v7`, `linux/riscv64`, …). Con un sshd de Alpine en
  esa plataforma se prueban de punta a punta los binarios que se descargan
  (hecho el 2026-09-27 en la subtarea 5 con arm y riscv64).
- **macOS y FreeBSD**: no hay máquina disponible. Solo compilar y vet cruzados
  (`GOOS=darwin`/`GOOS=freebsd go vet ./...`); anotar como no verificado en
  ejecución.
- Skills instaladas útiles: `golang-concurrency`, `golang-testing`,
  `golang-security` (revisar cada subtarea con ellas); `android-cli` para la
  verificación en Android.

## Contrato de errores del agente (común a 3, 4 y 6)

Para que el cliente explique por qué no hay nivel 3 (subtarea 6), cuando el
front no puede dar servicio **termina con código distinto de 0 y escribe en
stderr una línea** con este formato estable:

```
TITAN_AGENT_ERROR <CÓDIGO> <mensaje legible>
```

Códigos del lado agente:

| Código | Cuándo | Subtarea |
|---|---|---|
| `E_STATE_DIR` | El directorio de estado no es seguro (no es del usuario, es un enlace o tiene acceso de grupo u otros) o no se puede crear | 2 |
| `E_LOCK` | El sistema no permite el candado (p. ej. NFS sin bloqueo) | 2 |
| `E_DAEMON_START` | El daemon no aparece en el plazo | 3 |
| `E_AUTH` | Fichero de estado ilegible o token rechazado | 3 |
| `E_JOB_NO_BREAKAWAY` | Windows: el job de la sesión mata a los hijos y no permite escapar | 4 |
| `E_NO_CONPTY` | Windows sin ConPTY (anterior a 10 1809 / Server 2019) | 1 / 4 |
| `E_PTY` | No se puede crear el PTY | 1 |

Si el daemon no puede abrir la sesión (`E_PTY`, `E_NO_CONPTY`), el front ya
está conectado a él y no escribe nada: el motivo, `<CÓDIGO> <mensaje>`, va en el
payload opcional del `BYE` que el daemon envía en lugar de `HELLO_OK`.

Códigos del lado cliente (subtarea 6, en `AgentDiagnostics`):

| Código | Cuándo |
|---|---|
| `E_UNSUPPORTED_TARGET` | El SO o la arquitectura del destino no tiene agente, o no se pudo detectar |
| `E_NO_BINARY` | Hay agente para el destino, pero la app no lo tiene y la descarga falló |
| `E_UPLOAD` | No se pudo copiar el binario al destino |
| `E_CHECKSUM` | El binario copiado no coincide con el SHA-256 esperado |
| `E_NOEXEC` | El destino no deja ejecutarlo (sh sale con 126 o 127: `noexec`) |
| `E_AGENT_EXIT` | El front terminó sin abrir la sesión y sin decir por qué (p. ej. AppLocker) |
| `E_SYSTEMD_KILL` | Aviso, no impide el nivel 3: `KillUserProcesses=yes` sin linger |

## Hallazgos de la investigación (para no repetirla)

- `golang.org/x/sys/windows` (v0.48.0) exporta `CreatePseudoConsole`,
  `ResizePseudoConsole`, `ClosePseudoConsole`, `NewProcThreadAttributeList`,
  `LockFileEx` y `QueryInformationJobObject` (en esta versión solo devuelve
  `error`). **No** exporta `IsProcessInJob`: se llama por
  `windows.NewLazySystemDLL("kernel32.dll").NewProc("IsProcessInJob")`.
  Constantes que conviene definir en el propio código:
  `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016`,
  `CREATE_BREAKAWAY_FROM_JOB = 0x01000000`, `DETACHED_PROCESS = 0x00000008`,
  `CREATE_NEW_PROCESS_GROUP = 0x00000200`, `JOB_OBJECT_LIMIT_BREAKAWAY_OK =
  0x0800`, `JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK = 0x1000`,
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000`.
- ConPTY exige **Windows 10 1809 / Windows Server 2019** o posterior
  (documentación de `CreatePseudoConsole` en Microsoft Learn).
- La sonda del experimento (desechable, ya borrada) demostró el uso de ConPTY
  desde Go con solo `x/sys/windows`; los detalles que funcionaron están en la
  subtarea 1.
- `gofrs/flock` usa `fcntl` en `aix || (solaris && !illumos)`, `flock` en el
  resto de Unix y `LockFileEx` en Windows; el envoltorio propio de la subtarea 2
  sigue ese mismo reparto (sin depender de `gofrs/flock`).
- Win32-OpenSSH: incidencias #1032, #1642, #1751 y #1464 se contradicen según la
  versión; el comportamiento real se midió en el experimento.
