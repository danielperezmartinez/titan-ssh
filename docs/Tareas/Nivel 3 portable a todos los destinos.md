---
Nombre: 'Nivel 3 portable a todos los destinos'
Estado: 'Pendiente'
Resumen: 'Tarea paraguas para implementar ADR-0009: que el agente titan-agent (nivel 3) funcione en Windows, macOS, BSD y cualquier Linux, y degrade con diagnóstico donde no pueda. Reúne el orden de las subtareas, el contexto común (estado del código actual, entornos de verificación, contrato de errores, hallazgos de la investigación y del experimento de Windows) para poder empezar en una sesión limpia sin repetir la investigación. Subtareas: PTY propio multiplataforma; instancia única y directorio de estado; punto de encuentro TCP loopback con token; daemon en Windows; instalación del agente en destinos Windows y multi-SO; diagnóstico cuando el nivel 3 no está disponible.'
Decisiones: 'Implementa [[ADR-0009 Agente de nivel 3 portable a todos los destinos]], que sustituye en parte a [[ADR-0008 Diseño del agente de resiliencia nivel 3]], y el empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]. El desacople en Windows se decidió con [[Experimento supervivencia de procesos en Win32-OpenSSH]]. Continúa [[Resiliencia nivel 3 agente propio en el destino]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:30:00+02:00
---

# Nivel 3 portable a todos los destinos

## Objetivo

Implementar [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y el
empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]
(`Propuesta`: falta decidir el origen de la descarga). **Leer
la ADR-0009 antes de empezar cualquier subtarea**: es la fuente de verdad del diseño.
Esta nota solo añade orden, contexto de implementación y lo aprendido al
investigar.

## Subtareas y orden

| # | Subtarea | Depende de |
|---|---|---|
| 1 | [[titan-agent PTY propio multiplataforma]] | — |
| 2 | [[titan-agent instancia única y directorio de estado]] | — |
| 3 | [[titan-agent punto de encuentro TCP loopback con token]] | 2 |
| 4 | [[titan-agent daemon en Windows]] | 1, 3 |
| 5 | [[Instalación del agente en destinos Windows y multi-SO]] | 4 (solo la prueba de punta a punta en Windows) |
| 6 | [[Diagnóstico cuando el nivel 3 no está disponible]] | 3, 4, 5 |

1 y 2 son independientes y pueden hacerse en cualquier orden. 5 puede avanzar
en paralelo a 4 (detección, SFTP, rutas), pero su verificación en Windows
necesita 4.

## Estado del código de partida (2026-09-23)

- **Punto de partida en git**: el estado del agente y del cliente del nivel 3
  descrito aquí quedó en `main` en los commits del 2026-09-23 (agente de nivel 3
  + integración en el cliente, y documentación de ADR-0009/ADR-0010). Comparar
  contra ellos. Las skills instaladas (`.agents/`, `.claude/skills/`,
  `skills-lock.json`) **no** están en git.
- Agente Go en `agent/` (módulo `github.com/danigar/titan-ssh/agent`, `go 1.22`,
  única dependencia `github.com/creack/pty v1.1.24`):
  - `cmd/titan-agent/main.go`: modos front (por defecto) y `--daemon`; flags
    `--socket`, `--buffer-bytes`, `--session` (informativo), `--version`;
    `version = "0.0.1"`.
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
- **Linux real**: host de pruebas `nocendland-petit` (Tailscale, usuario
  `nocend`, clave `~/.ssh/nocendland-petit`). El 2026-09-23 rechazaba el puerto
  22; comprobar antes de contar con él.
- **Windows como destino SSH — método de prueba por decidir.** El usuario
  quiere explorar otras opciones y lo decidirá al llegar a las subtareas 4 y 5:
  **preguntarle antes de montar nada**. Opción ya probada, por si se elige: el
  `sshd` local (`OpenSSH_for_Windows_10.0p2`, servicio automático,
  `D:\Descargas\OpenSSH-Win64\OpenSSH-Win64`). Solo clave
  (`PasswordAuthentication no`), claves en `C:\Users\<u>\.ssh\authorized_keys`
  (también para administradores: no hay bloque `Match Group administrators`),
  shell por defecto `cmd.exe`. Para probar **sin administrador** hace falta un
  usuario estándar temporal; los pasos (crear usuario, `runas` para crear el
  perfil y autorizar la clave, limpieza) están en
  [[Experimento supervivencia de procesos en Win32-OpenSSH]]. **Al terminar,
  reiniciar `sshd` o el PC antes de borrar el perfil**: sshd lo deja cargado y
  el borrado falla con "archivo en uso".
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

Los códigos del lado cliente (SO no soportado, subida fallida, checksum,
`noexec`, `KillUserProcesses`) se definen en la subtarea 6.

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
