# titan-agent — agente de resiliencia nivel 3

Binario del destino para el **nivel 3** del modelo de resiliencia de titan-ssh
(ver `docs/Decisiones/ADR-0008 Diseño del agente de resiliencia nivel 3.md` y la
tarea `docs/Tareas/Resiliencia nivel 3 agente propio en el destino.md`).

> **Estado: FUNCIONAL** (núcleo del agente). Protocolo por tramas
> (`internal/protocol`), ring buffer (`internal/buffer`), sesión con PTY real +
> tee vivo + reenganche (`internal/session`) y el binario daemon/front
> (`cmd/titan-agent`) están implementados y **verificados en host real**
> (crea PTY, teea el prompt, aplica input y, al reconectar, el daemon sobrevive y
> reproduce el historial). Falta para el nivel 3 completo: instalación versionada
> + checksum, y el transporte del lado cliente (subtareas del nivel 3).

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
│   ├── main.go            # modos: front (default) / daemon
│   ├── daemon.go          # listen UDS + serve del protocolo por conexión
│   ├── front.go           # dial-or-spawn del daemon + empalme de stdio
│   └── detach_{unix,other}.go   # SysProcAttr setsid (unix) / no-op
└── internal/
    ├── protocol/          # códec de tramas (espejo de AgentProtocol.kt) + tests
    ├── buffer/            # ring buffer de salida con offsets + tests
    └── session/           # Registry + Session (PTY, tee vivo, reenganche) + tests
        ├── pty_unix.go    # PTY real (creack/pty), build tag unix
        └── pty_other.go   # stub para no-unix (compila/testea en Windows)
```

El formato de cable es idéntico al del cliente en
`shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt`; **mantener
ambos en sincronía**.

## Modelo de ejecución

`exec titan-agent` corre en modo **front**: empalma su stdio (el canal SSH) al
**daemon** por un socket Unix (`$XDG_RUNTIME_DIR/titan-agent.sock`), lanzándolo
detached (`setsid`) en el primer uso. El daemon sostiene los PTY y el ring buffer
por sesión y habla el protocolo por tramas; sobrevive a la desconexión del front,
así que reconectar reengancha por id (el id viaja en el `HELLO`) y reproduce
desde el offset del cliente.

## Build / test

```sh
cd agent
go test ./...               # protocolo, buffer y session (proxy con PTY fake)
# binarios multi-arch (estáticos, ~2.5 MB):
GOOS=linux  GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-amd64 ./cmd/titan-agent
GOOS=linux  GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-arm64 ./cmd/titan-agent
```

## Pendiente (subtareas del nivel 3)

- **Distribución**: build multi-arch empaquetado, subida por SFTP a ruta
  versionada y verificación de checksum (`titan-agent distribución multi-arch e
  instalación`).
- **Lado cliente**: `AgentTransport` sobre `AgentProtocol` + el canal `exec` (ya
  hecho en el motor SSH), y la integración en el flujo de resiliencia — hoy
  `ResilienceLevel.AGENT` degrada al nivel 2 (`Nivel 3 transporte cliente e
  integración de resiliencia`).
- Mejora menor: capar el buffer por el mínimo de los `ACK` (hoy capa por bytes).
