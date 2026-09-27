# titan-agent — agente de resiliencia nivel 3

Binario del destino para el **nivel 3** del modelo de resiliencia de titan-ssh
(ver `docs/Decisiones/ADR-0008 Diseño del agente de resiliencia nivel 3.md` y la
tarea `docs/Tareas/Resiliencia nivel 3 agente propio en el destino.md`).

> **Estado: FUNCIONAL** (núcleo del agente). Protocolo por tramas
> (`internal/protocol`), ring buffer (`internal/buffer`), sesión con PTY real +
> tee vivo + reenganche (`internal/session`) y el binario daemon/front
> (`cmd/titan-agent`) están implementados y **verificados en host real**
> (crea PTY, teea el prompt, aplica input y, al reconectar, el daemon sobrevive y
> reproduce el historial). La instalación versionada con checksum y el
> transporte del lado cliente también están hechos. Se está haciendo portable
> a todos los destinos (ADR-0009): ver **Pendiente**.

## Qué es (ADR-0008, opción B)

El cliente titan-ssh conecta por SSH normal (sshj) y hace `exec` de este binario
sobre el canal ya autenticado — **sin puerto ni auth propios**. Cliente y agente
hablan un protocolo binario por tramas sobre el stdio de ese canal exec. El
agente mantiene un PTY vivo por sesión, guarda su salida en un ring buffer y, al
reconectar, reproduce desde el offset que el cliente confirmó.

Es un **binario nativo estático** (Go), no un agente JVM: no exige Java en el
destino y la distribución multi-arch es trivial. Esto anula la reserva de MINA
SSHD que ADR-0004 había hecho para el agente (el cliente sigue en sshj).

## Estructura

```
agent/
├── go.mod / go.sum
├── cmd/titan-agent/
│   ├── main.go            # modos: front (default) / --daemon / --stop
│   ├── daemon.go          # candado + listen TCP loopback + serve del protocolo por conexión
│   ├── front.go           # dial-or-spawn del daemon + empalme de stdio; --stop
│   ├── rendezvous.go      # preámbulo con token entre front y daemon
│   ├── state.go           # agent.json: escritura atómica y validación
│   ├── lock*.go           # candado de instancia única (flock / fcntl / LockFileEx)
│   ├── statedir_*.go      # directorio de estado por sistema y su comprobación
│   ├── errors.go          # códigos TITAN_AGENT_ERROR del front
│   └── detach_*.go        # desacople del daemon: setsid (unix), breakaway del job (windows)
└── internal/
    ├── protocol/          # códec de tramas (espejo de AgentProtocol.kt) + tests
    ├── buffer/            # ring buffer de salida con offsets + tests
    └── session/           # Registry + Session (PTY, tee vivo, reenganche) + tests
        ├── pty.go         # PtyError con los códigos E_PTY / E_NO_CONPTY
        ├── pty_unix.go    # PTY Unix común: shell de login, TIOCSWINSZ, EIO → EOF
        ├── pty_{linux,darwin,freebsd}.go  # apertura del master y del esclavo por sistema
        ├── pty_windows.go # ConPTY (CreatePseudoConsole + CreateProcess)
        ├── pty_other.go   # stub (E_PTY) para los sistemas sin backend
        └── pty_contract*_test.go  # tests de contrato contra el PTY real
```

El PTY es un envoltorio propio y fino sobre el sistema (ADR-0009 §4). La única
dependencia del módulo es `golang.org/x/sys`, del proyecto Go; cualquier otra
necesita una ADR. En Unix la shell es `$SHELL -il` (o `/bin/sh -il`); en
Windows, la `DefaultShell` de OpenSSH o `%COMSPEC%`, y ConPTY exige Windows 10
1809 / Server 2019 o posterior (si no, `E_NO_CONPTY`).

El formato de cable es idéntico al del cliente en
`shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt`; **mantener
ambos en sincronía**.

## Modelo de ejecución

`exec titan-agent` corre en modo **front**: empalma su stdio (el canal SSH) al
**daemon**, lanzándolo desacoplado en el primer uso: `setsid` en Unix y, en
Windows, `CREATE_BREAKAWAY_FROM_JOB` para salir del Job Object con el que
Win32-OpenSSH mata los procesos de la sesión al cerrarla. Antes de lanzarlo en
Windows, el front mira los flags de su job: si el job mata al cerrarse y no
deja salir, termina con `E_JOB_NO_BREAKAWAY`. El daemon
sostiene los PTY y el ring buffer por sesión y habla el protocolo por tramas;
sobrevive a la desconexión del front, así que reconectar reengancha por id (el
id viaja en el `HELLO`) y reproduce desde el offset del cliente (ADR-0009 §2-3):

- **Un solo daemon por usuario.** Al arrancar, el daemon toma un candado
  exclusivo no bloqueante sobre `agent.lock` y lo mantiene mientras vive. Si ya
  lo tiene otro, sale con 0 sin tocar nada. El kernel lo suelta al morir el
  proceso, también con `kill -9`.
- **Front y daemon se encuentran por TCP en `127.0.0.1`.** El daemon escucha en
  un puerto aleatorio, genera un token de 32 bytes y, ya escuchando, publica
  `{schema, agent, port, token, pid}` en `agent.json` (fichero temporal +
  `rename`). El front lee el fichero, conecta y envía un preámbulo: la marca
  `TTNAGNT1` y el token. El daemon lo compara en tiempo constante y responde
  `TTNAGOK1`.
  Sin preámbulo válido en 10 s cierra la conexión sin responder. El protocolo
  cliente-agente no cambia: el token nunca sale del destino.
- Si no hay fichero, o nadie responde en su puerto, el front lanza un daemon
  (solo si el candado está libre) y vuelve a leer el fichero hasta 10 s.

### Ficheros de estado

En el directorio de estado del usuario, que se crea `0700` y se rechaza si no
es un directorio real del usuario sin acceso de grupo ni otros:

| Sistema | Directorio |
|---|---|
| Unix | `$XDG_STATE_HOME/titan-ssh` o `~/.local/state/titan-ssh` |
| Windows | `%LOCALAPPDATA%\titan-ssh` |

Contiene `agent.lock` (el candado) y `agent.json` (`0600`, con el token). No
se usa `/run/user` (systemd lo borra al cerrar la última sesión) ni `/tmp`.
`--state-dir` cambia el directorio (tests). `titan-agent --stop` termina el
daemon: primero se autentica contra `agent.json`, así que el PID que mata es
seguro el del daemon, y después borra el fichero.

### Errores

Si el front no puede dar servicio, termina con código distinto de 0 y una línea
en stderr: `TITAN_AGENT_ERROR <código> <mensaje>`. Códigos de este binario:
`E_STATE_DIR`, `E_LOCK`, `E_DAEMON_START`, `E_AUTH` y, en Windows,
`E_JOB_NO_BREAKAWAY` (tabla completa en la
tarea `Nivel 3 portable a todos los destinos`).

## Build / test

```sh
cd agent
go test ./...               # protocolo, buffer, session (PTY real y fake) y front/daemon
# binarios multi-arch (estáticos, ~2.5 MB):
GOOS=linux  GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-amd64 ./cmd/titan-agent
GOOS=linux  GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-arm64 ./cmd/titan-agent
```

## Pendiente

Lo que falta del nivel 3 portable (ADR-0009) está en la tarea
`docs/Tareas/Nivel 3 portable a todos los destinos.md`: la instalación en
destinos Windows y multi-SO, y el diagnóstico cuando el nivel 3 no está
disponible.

- Mejora menor: capar el buffer por el mínimo de los `ACK` (hoy capa por bytes).

## Licencia

Copyright (C) 2026 Daniel Pérez Martínez. Forma parte de titan-ssh y se
distribuye bajo la licencia **GPL-3.0-or-later**, sin ninguna garantía; ver
[`LICENSE`](../LICENSE) y los avisos de terceros en
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
