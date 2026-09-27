---
Nombre: 'titan-agent punto de encuentro TCP loopback con token'
Estado: 'Hecha'
Resumen: 'Subtarea 3 de ADR-0009 (§2): el front y el daemon dejan de encontrarse por un socket Unix en /run/user o /tmp y pasan a TCP 127.0.0.1 en puerto aleatorio, autenticado con un token de 32 bytes (crypto/rand) que el daemon escribe con {versión, puerto, token, pid} en agent.json dentro del directorio de estado (escritura atómica, solo tras escuchar). El front lee el fichero, conecta y envía un preámbulo (marca fija + token); el daemon lo compara en tiempo constante y responde con un ack, y solo entonces el front empalma los bytes. El protocolo cliente-agente no cambia y el token nunca sale del destino. Incluye --stop (se autentica antes de matar por PID), quita el socket Unix y listenPrivate, y actualiza tests y README. Hecha el 2026-09-27 y probada de punta a punta en Linux con el cliente Kotlin contra un sshd en Docker.'
Decisiones: 'Implementa §2 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. El protocolo cliente-agente sigue siendo el de [[AgentProtocol]] (HELLO primero). Contexto común en [[Nivel 3 portable a todos los destinos]]. Se añade un ack del daemon al preámbulo (TTNAGOK1): sin él, el front no distingue un token rechazado ni un puerto de un fichero viejo que ahora usa otro proceso.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-27T15:00:00+02:00
---

# titan-agent: punto de encuentro TCP loopback con token

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa §2 de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. Necesita el
directorio de estado y el candado de
[[titan-agent instancia única y directorio de estado]].

## Por qué

El socket Unix no existe igual en Windows, en macOS tiene un límite de ruta de
104 bytes, y en Linux con systemd `/run/user/<uid>` se borra al cerrar la última
sesión: el daemon queda vivo pero inalcanzable y se pierden las sesiones. El TCP
de loopback es el mismo código en todos los sistemas y su estado vive en el
perfil del usuario, que sobrevive al cierre de sesión.

## Diseño

### Daemon (`runDaemon`)

1. `ensureStateDir` + candado (subtarea 2). Sin candado → salir con 0.
2. `net.Listen("tcp", "127.0.0.1:0")`. **Solo `127.0.0.1`**: nunca `0.0.0.0`
   (expondría el puerto a la red y en Windows dispara el aviso del cortafuegos).
3. Token: 32 bytes de `crypto/rand`, en hexadecimal (64 caracteres). Nuevo en
   cada arranque del daemon.
4. Escribir `agent.json` de forma **atómica** y **solo después** de estar
   escuchando: fichero temporal en el mismo directorio (`0600`, `O_EXCL`),
   `Sync`, `rename` sobre `agent.json`. Formato propuesto:

   ```json
   {"schema": 1, "agent": "0.0.1", "port": 49152, "token": "<64 hex>", "pid": 1234}
   ```

5. Por cada conexión: leer el preámbulo con plazo (reutilizar `helloTimeout`)
   antes de pasar a `serveConn`. Preámbulo propuesto: 8 bytes de marca fija
   `TTNAGNT1` + 32 bytes de token en crudo. Comparar con
   `crypto/subtle.ConstantTimeCompare`; si no coincide o no llega → cerrar sin
   responder. Después, `serveConn` sin cambios (HELLO primero, fuga previa ya
   corregida).
6. Al salir de forma ordenada, borrar `agent.json` solo si su `pid` es el
   propio. No imprescindible: si queda un fichero viejo, el front no conecta y
   arranca otro daemon, que toma el candado y lo reescribe.

### Front (`runFront` / `dialOrSpawn`)

1. `ensureStateDir`.
2. Leer `agent.json` → conectar a `127.0.0.1:<port>` → enviar el preámbulo.
3. Si no hay fichero o la conexión se rechaza → lanzar el daemon desacoplado
   (hoy `setsid`; en Windows, lo que decida la subtarea 4) y **volver a leer el
   fichero en cada intento** del sondeo (hoy 5 s cada 50 ms): el daemon nuevo
   escribe un puerto y un token nuevos.
4. Si pasa el plazo → `TITAN_AGENT_ERROR E_DAEMON_START ...`. Fichero
   ilegible o token rechazado → `E_AUTH`.
5. Empalme de stdio ↔ conexión como hoy (`io.Copy` en ambos sentidos).

### `--stop`

Leer `agent.json` y terminar el daemon por PID (`os.FindProcess(pid).Kill()`).
Sirve para desinstalar y para la limpieza de los tests. En Windows,
`tasklist` y `taskkill /im` dan "Acceso denegado" desde una sesión SSH, pero
terminar el propio proceso por PID sí funciona (comprobado con `Stop-Process` en
el experimento).

### Lo que se quita

- `listenPrivate` y la umask (`cmd/titan-agent/sock_unix.go`) y el flag
  `--socket`; en su lugar, `--state-dir` para tests.
- `defaultSocketPath` en `main.go`.

## Seguridad (revisar con la skill `golang-security`)

- Otro usuario local puede llegar al puerto, pero sin el token no pasa del
  preámbulo. El token solo está en `agent.json` (`0600` dentro de un directorio
  `0700` del usuario; en Windows, dentro del perfil privado).
- El token nunca viaja por SSH ni se registra en logs.
- Comparación en tiempo constante; plazo para el preámbulo (sin él, una
  conexión que no envía nada ocuparía recursos, como la fuga previa al HELLO).

## Tests

- Portables (Windows y Linux): arranque de daemon en un directorio temporal
  (`--state-dir`), front que conecta y completa HELLO/HELLO_OK; token incorrecto
  → conexión cerrada; sin preámbulo → cerrada tras el plazo; fichero de estado
  viejo (puerto muerto) → el front arranca otro daemon y conecta.
- Reconexión: la sesión sigue viva tras cerrar el front y volver a abrirlo.
- Kotlin: `AgentTransportIntegrationTest` limpia con
  `pkill -x titan-agent; rm -f /run/user/1000/titan-agent.sock $path`
  (línea 88): cambiar a `<binario> --stop` y borrar el directorio de estado.
- Punta a punta en Linux (Docker o `<host-de-pruebas>`) con el binario real.

## Criterios de finalización

- Sin sockets Unix en el agente; front y daemon hablan por TCP de loopback con
  token en Linux y Windows (el desacople en Windows llega con la subtarea 4:
  aquí basta con que funcione mientras la sesión sigue abierta).
- Tests verdes en Windows local y Linux (Docker, `-race`), punta a punta en
  Linux.
- `agent/README.md` actualizado (modelo de ejecución y ficheros de estado).

## Resultado (2026-09-27)

Hecha en la misma sesión que
[[titan-agent instancia única y directorio de estado]].

### Código (`agent/cmd/titan-agent/`)

- `daemon.go`: `startDaemon` toma el candado, escucha en `127.0.0.1:0`, genera
  el token y solo entonces publica `agent.json`. `serve` pasa cada conexión
  por el preámbulo antes de `serveConn`, que no cambia.
- `rendezvous.go`: `dialDaemon` (front) y `acceptPreamble` (daemon).
- `state.go`: `agent.json` con el formato propuesto (`schema`, `agent`,
  `port`, `token`, `pid`), escrito con `os.CreateTemp` (`O_EXCL`, `0600`),
  `Sync` y `rename`, y validado al leerlo.
- `front.go`: `dialOrSpawn`, `spawnDaemon` y `stopDaemon` (`--stop`).
- `main.go`: flags `--state-dir` y `--stop`. Se quitan `--socket`,
  `defaultSocketPath`, `listenPrivate` y la umask.
- `AgentTransportIntegrationTest` (Kotlin) limpia con `<binario> --stop`.

### Cambios sobre el diseño

- **Ack del daemon**. Tras validar el token, el daemon responde con la marca
  `TTNAGOK1`, y el front solo empalma cuando la recibe. Sin ack, el front no
  podía distinguir un token rechazado (el daemon cierra sin responder, igual
  que al aceptar se queda callado esperando el `HELLO`). Peor aún: si el
  puerto de un `agent.json` viejo lo había cogido otro proceso, el front le
  habría mandado los bytes del cliente. Solo afecta al tramo front-daemon.
- **Plazo del front: 10 s** (antes 5 s). Si tras 1,5 s sigue sin daemon y el
  candado está libre, vuelve a lanzarlo.
- **`E_AUTH`** = el candado está tomado (hay daemon vivo), pero el fichero no se
  puede leer o el token no se acepta. Todo lo demás es `E_DAEMON_START`.
- **`--stop`** se autentica contra `agent.json` antes de matar por PID. Si la
  conexión con el token funciona, el fichero es del daemon vivo y el PID es el
  suyo, nunca uno reciclado. Después espera a que se libere el candado y borra
  el fichero. Si no hay daemon, borra el fichero viejo y sale con 0.
- Un fallo pasajero de `Accept` (p. ej. sin descriptores libres) ya no termina
  el daemon, que se llevaría todas las sesiones.
- El front recoge con `Wait` al daemon que lanza, para no dejar zombis si
  pierde la carrera del candado.
- La limpieza de los tests Kotlin no borra el directorio de estado, como
  proponía la sección Tests: es el directorio real del usuario en el host de
  pruebas, y `--stop` ya borra `agent.json`. Solo queda `agent.lock`, vacío.
- **Windows**: funciona mientras la sesión sigue abierta. El front lanza el
  daemon con `detachAttr()`, que en Windows sigue vacío. El desacople se
  cablea ahí en [[titan-agent daemon en Windows]].

### Migración desde versiones anteriores

Si en el destino sigue vivo un daemon de una versión anterior (socket Unix), el
front nuevo no lo encuentra y arranca otro. Las sesiones del viejo se pierden
una sola vez: la pestaña recibe `created=true` y vuelve a ejecutar sus scripts
de inicio. El daemon viejo queda huérfano hasta que se reinicie el destino o
se mate a mano. A partir de esta versión, un front de una versión posterior
conecta con el daemon que ya esté vivo, así que las sesiones sobreviven a las
actualizaciones de la app.

### Seguridad

Revisión con los criterios de la skill `golang-security`: token de 256 bits de
`crypto/rand`, en un fichero `0600` dentro de un directorio `0700` del usuario;
comparación en tiempo constante; preámbulo con plazo (`helloTimeout`); solo
`127.0.0.1`; el token nunca va en logs ni por SSH. Con un `agent.json` viejo,
el preámbulo sí llega a quien ocupe ese puerto, pero ese token ya no sirve: su
daemon está muerto (si no, seguiría ocupando el puerto) y cada daemon nuevo
genera uno distinto. Queda anotado en el código.

### Verificación

- Tests portables: el daemon publica su puerto de loopback; front a daemon con
  `HELLO`/`HELLO_OK`; token o marca incorrectos, silencio o un `HELLO` sin
  preámbulo → conexión cerrada sin respuesta; `agent.json` viejo (puerto
  muerto) → se lanza un daemon y conecta; daemon vivo → no se lanza otro; sin
  daemon → `E_DAEMON_START`; token rechazado con el candado tomado → `E_AUTH`;
  directorio inutilizable → `E_STATE_DIR`. De punta a punta con procesos
  reales (el binario de test hace de agente): el primer front lanza el daemon
  y crea la sesión, un segundo front se reengancha a ella (`created=false`), y
  `--stop` termina el daemon y borra `agent.json`.
- Verdes en Windows local y en Linux (Docker, `-race`, cinco repeticiones).
- **Punta a punta en Linux con el binario real**. El host de pruebas no
  respondía, así que se usó un sshd temporal en Docker con una clave
  desechable. Pasan `AgentTransportIntegrationTest` (sus dos tests: marca
  reproducida al reconectar, y scripts de inicio una sola vez) y
  `AgentInstallerIntegrationTest`. En el contenedor: el daemon queda con PPID
  1 y sobrevive al cierre de la sesión SSH, `--stop` lo termina, y no queda
  ningún socket Unix.
- Hallazgo: la limpieza anterior de los tests Kotlin (`pkill -x titan-agent`)
  no mataba nada, porque el binario instalado se llama
  `agent-<versión>-<os>-<arch>`.
