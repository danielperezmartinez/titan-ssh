---
Nombre: 'titan-agent instancia única y directorio de estado'
Estado: 'Pendiente'
Resumen: 'Subtarea 2 de ADR-0009 (§3): un único daemon por usuario mediante un candado exclusivo no bloqueante sobre agent.lock, con envoltorio propio (flock en Linux/macOS/BSD, fcntl F_SETLK en AIX/Solaris, LockFileEx en Windows), y el directorio de estado privado del usuario donde viven el candado y el fichero de estado (Unix: $XDG_STATE_HOME/titan-ssh o ~/.local/state/titan-ssh; Windows: %LOCALAPPDATA%\titan-ssh). El daemon que no consigue el candado sale con 0 sin tocar nada. Arregla la carrera de dos daemons (el segundo borraba el socket del primero y lo dejaba huérfano). Reutiliza ensureSocketDir como comprobación del directorio de estado.'
Decisiones: 'Implementa §3 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. Resuelve el fallo previo anotado en [[titan-agent fuga previa al HELLO y permisos del socket]]. Contexto común en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:05:00+02:00
---

# titan-agent: instancia única y directorio de estado

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa §3 de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]].

## Problema que resuelve

Hoy (`cmd/titan-agent/front.go`, `dialOrSpawn`), si dos fronts no encuentran el
daemon a la vez, cada uno lanza uno. `runDaemon` hace `os.Remove(sockPath)`
antes de `Listen`, así que el segundo borra el socket del primero: el primero
queda huérfano para siempre con sus sesiones (no hay forma de volver a
conectarse a él y no termina nunca). Al reconectar, el cliente cae en el segundo,
que no conoce la sesión, y se pierde justo lo que el nivel 3 debe conservar.

## Diseño

### Directorio de estado

- Unix: `$XDG_STATE_HOME/titan-ssh` si está definido; si no,
  `~/.local/state/titan-ssh` (`os.UserHomeDir`). **No** `/run/user` (systemd lo
  borra al cerrar la última sesión) ni `/tmp`.
- Windows: `%LOCALAPPDATA%\titan-ssh` (`os.Getenv("LOCALAPPDATA")`; no usar
  `os.UserCacheDir`, que en Linux apunta a `~/.cache`, un directorio que se
  puede limpiar).
- Se crea `0700` y se comprueba con la lógica actual de `ensureSocketDir`
  (`cmd/titan-agent/sock_unix.go`): directorio real (no enlace simbólico), del
  uid actual, sin acceso de grupo ni otros (`0o077`). Renombrar a algo como
  `ensureStateDir`. En Windows, el perfil ya es privado por ACL; valorar
  comprobar el propietario con `x/sys/windows` (`GetNamedSecurityInfo`) o
  dejarlo anotado.
- Si falla → `TITAN_AGENT_ERROR E_STATE_DIR ...` (contrato de errores en
  [[Nivel 3 portable a todos los destinos]]).

### Candado

- Fichero `agent.lock` en el directorio de estado, abierto con
  `O_CREATE|O_RDWR`, `0600`.
- Candado **exclusivo y no bloqueante**, mantenido mientras vive el daemon (el
  descriptor no se cierra nunca). El kernel lo libera al morir el proceso,
  también con `kill -9` o un fallo: no hay ficheros PID ni candados huérfanos.
- Envoltorio propio, con el mismo reparto que usa `gofrs/flock` (comprobado en
  su código fuente):
  - `flock(fd, LOCK_EX|LOCK_NB)` → Linux, macOS y BSD (`syscall.Flock`).
  - `fcntl(F_SETLK, F_WRLCK)` → `aix || (solaris && !illumos)`.
  - `windows.LockFileEx(h, LOCKFILE_EXCLUSIVE_LOCK|LOCKFILE_FAIL_IMMEDIATELY, ...)`
    → Windows.
- Resultado del intento:
  - **Conseguido** → este proceso es el daemon: ya puede limpiar restos de un
    daemon anterior y reescribir el fichero de estado (subtarea 3).
  - **Ocupado** (`EWOULDBLOCK` / `ERROR_LOCK_VIOLATION`) → ya hay un daemon
    vivo: salir con código 0, sin tocar nada. El front ya estaba esperando y
    conectará con el existente.
  - **Otro error** (p. ej. NFS sin bloqueo) → `E_LOCK`.
- Riesgo conocido: home en **NFS**. El candado depende del servidor; si falla,
  degradar con `E_LOCK` en vez de arrancar sin candado.

### Mientras no exista la subtarea 3

Esta subtarea puede entregarse sola aplicando el candado al diseño actual: el
daemon toma el candado **antes** del `os.Remove(sockPath)`, así el borrado solo
lo hace el ganador. Cuando llegue el TCP (subtarea 3), el candado se queda y el
socket desaparece.

## Tests

- Dos "daemons" en el mismo directorio (en el test, dos llamadas a la función de
  arranque o dos procesos): el segundo detecta el candado ocupado y sale con 0;
  el primero sigue sirviendo.
- Tras matar al primero (proceso hijo del test con `Kill`), un nuevo intento
  consigue el candado.
- `ensureStateDir`: los tests de `sock_unix_test.go` (directorio nuevo privado,
  rechazar 0777/0750/0701, rechazar enlace simbólico) pasan a este directorio.
- Ejecutar en Windows local y en Linux (Docker, `-race`).

## Criterios de finalización

- Nunca hay dos daemons por usuario (test de concurrencia verde en Windows y
  Linux).
- El directorio de estado se crea y valida en todos los sistemas; en
  Solaris/AIX al menos compila (`GOOS=solaris`/`GOOS=aix go vet`).
- Errores `E_STATE_DIR` y `E_LOCK` emitidos con el formato del contrato.
