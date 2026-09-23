---
Nombre: 'titan-agent fuga previa al HELLO y permisos del socket'
Estado: 'Hecha'
Resumen: 'Dos fallos del daemon de titan-agent, corregidos y verificados en Linux (contenedor golang:1.27, usuario sin privilegios, go test -race). (1) Fuga en serveConn: si el cliente cerraba, callaba o enviaba otra trama antes del HELLO, la conexión y su goroutine lectora quedaban colgadas para siempre (helloCh nunca se cerraba; con más de 64 tramas previas el lector se bloqueaba en el envío). Ahora el lector cierra helloCh si no hubo HELLO, hay plazo de 10 s para el HELLO, una trama previa al HELLO corta la conexión, y serveConn espera a su lector antes de volver. (2) Permisos del socket: el socket se creaba con Listen y se restringía después con Chmod (ventana abierta y error descartado), y la ruta alternativa /tmp/titan-ssh-<uid> se aceptaba aunque la hubiera creado otro usuario (falso daemon que vería las teclas, o acceso a la shell). Ahora el socket nace 0600 (umask durante bind) y el daemon y el front exigen que el directorio sea real, del usuario y sin acceso de grupo u otros.'
Decisiones: 'Endurece lo entregado en [[titan-agent PTY y daemonización]] dentro de [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (directorios 0700, solo espacio de usuario); no cambia la decisión. Hace obligatorio que HELLO sea la primera trama, anotado en [[AgentProtocol]].'
Bloqueada: []
Fecha de creación: 2026-09-23T19:45:00+02:00
Última modificación: 2026-09-23T20:10:00+02:00
---

# titan-agent: fuga previa al HELLO y permisos del socket

## Objetivo

Corregir dos fallos del daemon del agente (nivel 3) detectados al revisar
`agent/` con las skills `golang-concurrency` y `golang-security`: una fuga de
conexiones y goroutines antes del `HELLO`, y permisos del socket Unix que
permitían a otro usuario local interceptar o suplantar el daemon.

## Criterios de finalización

- `serveConn` vuelve siempre, y solo después de que su goroutine lectora haya
  terminado, en todos los casos: cliente que cierra antes del `HELLO`, cliente
  que no envía nada (plazo `helloTimeout`, 10 s), trama distinta de `HELLO` como
  primera trama, y `Handle` que termina mientras el cliente sigue enviando. ✅
  (`cmd/titan-agent/daemon.go`)
- El socket nace con `0600` (umask `0177` solo durante `Listen`, se restaura
  enseguida para que las shells no la hereden); el error de `Chmod` ya no se
  descarta. ✅ (`listenPrivate`, `cmd/titan-agent/sock_unix.go`)
- Daemon **y front** validan el directorio del socket antes de usarlo: debe ser
  un directorio real (no enlace simbólico), del uid actual y sin permisos de
  grupo u otros (`0o077`). Las rutas por defecto (`$XDG_RUNTIME_DIR` o la
  alternativa creada con `0700`) pasan; un directorio ajeno se rechaza. ✅
  (`ensureSocketDir`; stub sin comprobaciones en `sock_other.go` para Windows)
- Tests: `daemon_test.go` (portable) y `sock_unix_test.go` (solo Unix). ✅

## Verificación

- **Windows** (`go vet ./...`, `go test ./...`): verde. Se comprobó que los tests
  de la fuga **fallan** sin el arreglo (`serveConn did not return`).
- **Linux** (Docker `golang:1.27`, usuario uid 1000): `go vet`, `go test -race
  ./...` verde (en Windows `-race` no funciona sin cgo), incluidos los tests Unix
  de directorio y socket.
- **Punta a punta con el binario real** en el mismo contenedor:
  1. Caso normal (sin `XDG_RUNTIME_DIR`): directorio `drwx------`, socket
     `srw-------`, el daemon responde `HELLO_OK`.
  2. Ataque: otro usuario pre-crea `/tmp/titan-ssh-1000` con `0777` → front y
     daemon se niegan (`socket dir … is owned by uid 1001, not 1000`, exit 1).
- El host de pruebas ([[ssh-test-host]]) no estaba disponible (sshd rechazaba la
  conexión), por eso la verificación Linux se hizo en contenedor.

## Notas

- Riesgo residual aceptado: un usuario que pre-crea el directorio alternativo en
  `/tmp` provoca **denegación de servicio** (el agente no arranca y la sesión
  baja a nivel 2/1), no acceso. Solo afecta a hosts sin `$XDG_RUNTIME_DIR`.
- Fuera de alcance (anterior a esta tarea): si dos fronts arrancan el daemon a
  la vez, el segundo borra el socket del primero y deja ese daemon huérfano.
