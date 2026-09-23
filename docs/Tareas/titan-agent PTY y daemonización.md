---
Nombre: titan-agent PTY y daemonización
Estado: Hecha
Resumen: 'ENTREGADO y verificado en host real. El binario Go del agente (agent/) es funcional: PTY real (creack/pty corriendo $SHELL -il) con Resize; arquitectura daemon UDS + front fino — cada `exec titan-agent` es un front que empalma su stdio al daemon por un socket Unix (/run/user/<uid> o temp), y el daemon (setsid, sobrevive al cierre del front) sostiene los PTY y el ring buffer por sesión; tee de salida viva a tramas DATA con offsets, concurrente con INPUT/RESIZE/ACK/REPLAY_FROM; GC de sesiones ociosas por TTL. Verificado headless (go test: session proxy con PTY fake) y de punta a punta contra nocendland-petit (subido linux/amd64: crea PTY y teea el prompt, INPUT llega al shell, y al RECONECTAR el daemon sobrevive y reproduce el historial desde offset 0). Cross-compila a linux amd64/arm64 y darwin/arm64 (~2.5 MB estáticos).'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]]; refina su §6 (ciclo de vida) con el modelo daemon-sobre-socket-Unix + front fino (el id de sesión viaja en el HELLO). Completa el esqueleto sembrado en [[Resiliencia nivel 3 agente propio en el destino]] (`agent/internal/session`).'
Bloqueada: []
Fecha de creación: 2026-09-19T18:25:00+02:00
Última modificación: 2026-09-23T20:10:00+02:00
---

# titan-agent: PTY y daemonización

## Objetivo

Hacer funcional el agente del destino: que mantenga un **PTY vivo** por sesión,
**sobreviva** a la desconexión del cliente y sirva output vivo + replay.

## Criterios de finalización

- `session.PtyFactory` real con `creack/pty` arrancando `$SHELL -il` (o `/bin/sh`),
  con tamaño inicial del `HELLO` y `Resize`. ✅ (`internal/session/pty_unix.go`;
  stub `pty_other.go` para no-unix, así el módulo compila/testea en Windows.)
- **Daemonización**: modelo **daemon UDS + front fino**. El front (cada `exec`)
  empalma stdio↔socket; el daemon corre con `setsid` (`detach_unix.go`) y
  sobrevive al cierre del front, manteniendo abiertos los PTY. ✅
- **Tee vivo**: `Session.Handle` reproduce desde el offset y luego emite `DATA`
  con la salida viva del PTY (tee vía `sync.Cond`), concurrente con
  `INPUT`/`RESIZE`/`ACK`/`REPLAY_FROM`. ✅
- **GC**: `Registry.GC(ttl)` cierra sesiones ociosas sin clientes; ticker en el
  daemon. ✅ (Capar el buffer por el mínimo de los `ACK` queda como mejora menor;
  hoy el ring buffer capa por bytes.)
- `go test ./...` verde. ✅

## Diseño de daemonización (refina ADR-0008 §6)

`exec titan-agent` es un **front** que hace `Dial("unix", $XDG_RUNTIME_DIR/titan-agent.sock)`;
si no hay daemon, lo lanza detached (`setsid`, stdio nulo) y espera al socket.
Luego empalma `stdin↔conn` y `conn↔stdout` — el protocolo por tramas fluye entre
el **cliente** y el **daemon**; el front es una tubería tonta que puede ir y
venir en cada (re)conexión. El **id de sesión viaja en el `HELLO`**, así que el
front es agnóstico al id. El daemon (`cmd/titan-agent/daemon.go`) mantiene el
`Registry` de sesiones (PTY + ring buffer) y habla el protocolo por conexión.

## Verificación

- **Headless** (`go test ./...`): `internal/protocol`, `internal/buffer` y
  `internal/session` (proxy con PTY fake: HELLO_OK, replay desde offset, tee vivo,
  INPUT→PTY, RESIZE, reenganche desde offset, reuse de sesión por id). Verde.
- **Cross-compile + vet** para `linux/amd64`, `linux/arm64` (compilan `pty_unix.go`
  + creack/pty) y `darwin/arm64`; binarios ~2.5 MB estáticos.
- **Host real** ([[ssh-test-host]], `nocendland-petit`, linux/amd64): subido el
  binario y hablado el protocolo por SSH.
  1. `HELLO` → `HELLO_OK` + `DATA@0` con el prompt real (`[nocend@…]$ `) → **PTY
     creado y teeado**; `INPUT("echo TITAN_MARKER")` → el shell lo ejecuta y su
     salida vuelve como `DATA` (offsets monótonos correctos).
  2. **Reconexión** (`HELLO` mismo id, sin teclear): el daemon seguía vivo
     (`--daemon --socket /run/user/1000/…`) y el `HELLO_OK` reportó head=91 con
     **replay del historial** (incluye `TITAN_MARKER`) → persistencia y reenganche
     confirmados. Host dejado limpio tras la prueba.

## Notas

- Falta para el nivel 3 completo: instalación versionada + checksum
  ([[titan-agent distribución multi-arch e instalación]]) y el transporte cliente
  ([[Nivel 3 transporte cliente e integración de resiliencia]]).
- Endurecimiento posterior del daemon (fuga de conexiones antes del `HELLO` y
  permisos del socket/directorio): [[titan-agent fuga previa al HELLO y permisos del socket]].
