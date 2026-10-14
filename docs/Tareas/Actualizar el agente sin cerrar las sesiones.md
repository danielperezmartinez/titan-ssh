---
Nombre: 'Actualizar el agente sin cerrar las sesiones'
Estado: 'Hecha'
Resumen: 'Hoy, al actualizar un agente de una versión anterior desde la app, se para el daemon y se cierran todas sus sesiones con lo que tuvieran corriendo. Se quiere poder actualizar sin perder nada: cada sesión pasa a vivir en su propio proceso guardián, que tiene el PTY y el búfer, y el daemon queda como enrutador que se puede sustituir por otra versión sin tocar las sesiones. Windows, Linux y macOS.'
Decisiones: 'El usuario eligió el 2026-10-14 separar las sesiones del daemon en procesos guardianes, frente a heredar los PTY con execve (no sirve en Windows) o dejar convivir dos daemons; recogido en [[ADR-0022 Procesos de sesión independientes del daemon del agente]], aceptada el mismo día. El daemon reenvía los bytes al guardián (no entrega el socket); cada guardián tiene su puerto, token, registro y candado. --stop sigue cerrando todo y --restart actualiza sin cerrar. En Unix los binarios viejos se borran con --remove-unused, que conserva los que usa un daemon o un guardián. La pestaña pregunta al agente si la sesión sigue viva antes de darla por terminada.'
Bloqueada: []
Fecha de creación: 2026-10-14T16:35:00+02:00
Última modificación: 2026-10-14T19:10:00+02:00
---

# Actualizar el agente sin cerrar las sesiones

## Objetivo

Que actualizar el agente de un destino no cierre ninguna sesión ni los
procesos que corren dentro. Hoy el aviso de versión anterior
([[Transparencia y control del agente en el destino]]) ofrece actualizar
avisando de que se cierran las sesiones, porque el daemon es el padre de
cada shell y guarda su PTY y su búfer. Diseño en
[[ADR-0022 Procesos de sesión independientes del daemon del agente]].

## Alcance

- Modo guardián de sesión en el binario del agente: crea el PTY o ConPTY y
  el shell, guarda el búfer, offsets, acks, títulos, tamaño y primer plano
  (lo que hoy hace `internal/session`).
- El daemon crea guardianes, enruta hacia ellos las conexiones de sesión y
  reúne su información para `status`, `close` y `preview`; ya no tiene PTY.
- Registro de cada guardián en el directorio de estado, con permisos y
  comprobaciones como `agent.json`; un daemon que arranca recoge los
  guardianes vivos.
- Protocolo daemon ↔ guardián mínimo y versionado; un daemon nuevo maneja
  guardianes de versiones anteriores.
- En la app, actualizar el agente deja de avisar de que se cierran las
  sesiones; cerrar todas las sesiones pasa a ser una acción aparte.
- `TITAN_AGENT` y la limpieza de versiones instaladas: no se borra una
  versión con guardianes vivos y los shells antiguos siguen encontrando el
  agente.
- Transición desde los daemons actuales, que tienen las sesiones dentro.

## Preguntas resueltas (2026-10-14)

- **Cómo pasa la conexión al guardián:** el daemon lee el `HELLO`, conecta con
  el guardián de esa sesión y desde ahí solo copia bytes en los dos sentidos.
  Más simple y portable que entregar el socket; el salto extra es local.
- **Cómo se contacta un guardián:** como el daemon, con su propio puerto
  loopback y su propio token, publicados en `session-<clave>.json` (la clave
  es un hash del id de sesión) y protegidos por `session-<clave>.lock`. El
  guardián anuncia al arrancar, por su stdout, su registro o el motivo por el
  que no pudo abrir la sesión.
- **Un registro cuyo guardián no responde:** si su candado está libre, el
  guardián murió y el daemon borra el registro (y, si llega un `HELLO`, abre
  una sesión nueva); si el candado sigue cogido, se informa del fallo y no se
  toca.

## Criterios de finalización

- Con sesiones abiertas (incluido un proceso de larga duración dentro),
  actualizar el agente a otra versión no cierra ninguna: la app reconecta y
  recibe la salida producida mientras tanto. Comprobado en Linux y en
  Windows.
- Las sesiones nuevas tras actualizar corren con la versión nueva.
- Matar el daemon a mano tampoco cierra las sesiones; el siguiente daemon las
  recupera.
- `status`, `close` y `preview` siguen funcionando con guardianes de la
  versión actual y de la anterior.
- Ningún guardián termina por tiempo ni por inactividad.
- Tests del agente (`go test -race`) en verde en Windows y Linux.

## Verificación

2026-10-14, en la rama `agent-session-hosts`:

- **Tests del agente:** `go test ./...` en verde en Windows (con ConPTY y
  procesos reales) y `go test -race -count=3 ./...` en verde en Linux
  (Docker). Los nuevos cubren que una sesión sobrevive a su daemon y el
  siguiente la recupera con su historial (`TestASessionOutlivesItsDaemon`),
  un guardián por sesión, `--stop` que cierra todos, `--restart` que los
  deja, el fin del guardián cuando su shell termina, el rechazo con motivo de
  un guardián que no arranca, un único guardián ante `HELLO` simultáneos,
  `--remove-unused` y, con procesos y shell reales,
  `TestRestartKeepsTheSessionAndItsShell` (`--restart` y matar el daemon a
  mano: la shell sigue con el mismo PID). Fuera de esta tarea,
  `TestRacingFrontsAgreeOnTheTaskName` falla a veces también en `main`.
- **Tests de la app:** `:shared:desktopTest` del paquete `terminal` en verde,
  con los nuevos de `AgentSessionDetailsTest` (contrato `sessionHosts` y
  `agent`), `SessionTabAgentAutomationTest` (tras un reinicio del daemon la
  pestaña se reengancha; si la sesión ya no está, termina) y
  `AgentInstallerTest` (`--remove-unused` en Unix).
- **Linux en el emulador** (`Pixel_9_Pro_XL`, build de debug, contenedor
  desechable de la imagen `titan-test-sshd` en el puerto 2223 con un agente
  `0.0.0-old` ya en marcha): sesión de nivel 3 con un contador en bucle; el
  instalador conserva el binario viejo porque lo usan el daemon y el
  guardián; el panel avisa del agente de otra versión y ofrece actualizar
  con «Las sesiones siguen abiertas»; tras actualizar, el daemon es el
  `0.0.0-dev`, la shell sigue con el mismo PID en su guardián `0.0.0-old`,
  el detalle dice «iniciada con el agente 0.0.0-old» y la pestaña se
  reengancha sola con el contador avanzando. Matar el daemon y arrancar otro
  también recupera la sesión.
- **Windows real por sshd** (cuenta estándar de pruebas, desde
  `127.0.0.1`): sesión creada con un agente `0.0.0-old` y un `ping` largo,
  que sigue vivo al cerrarse la conexión SSH (Job Object de sshd); `--restart`
  con un binario `0.0.0-new` deja el daemon nuevo y la sesión con la misma
  shell, el `ping` en marcha y `agent` `0.0.0-old`. Limpiado después.

## Resultado

Integrada en `main` el 2026-10-14, a petición del usuario tras la prueba en
el emulador. Cada sesión del agente vive en un guardián (`--session-host`,
`agent/cmd/titan-agent/host.go`) y el daemon solo enruta (`hosts.go`);
`--restart` actualiza el daemon sin cerrar sesiones (`update.go`) y
`--remove-unused` borra en Unix los binarios que nada usa. En la app,
«Actualizar» usa `--restart` con los agentes que lo admiten (`sessionHosts`
en el estado), el detalle de la sesión dice con qué versión arrancó y la
pestaña se reengancha cuando solo cambió el daemon.

Los agentes anteriores (hasta `0.1.0-beta.16`) guardan las sesiones dentro
del daemon: la primera actualización a esta versión aún las cierra; las
siguientes ya no. La comprobación con la APK de un Release queda para cuando
se publique la versión.
