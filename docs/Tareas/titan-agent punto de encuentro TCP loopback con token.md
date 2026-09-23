---
Nombre: 'titan-agent punto de encuentro TCP loopback con token'
Estado: 'Pendiente'
Resumen: 'Subtarea 3 de ADR-0009 (§2): el front y el daemon dejan de encontrarse por un socket Unix en /run/user o /tmp y pasan a TCP 127.0.0.1 en puerto aleatorio, autenticado con un token de 32 bytes (crypto/rand) que el daemon escribe con {versión, puerto, token, pid} en agent.json dentro del directorio de estado (escritura atómica, solo tras escuchar). El front lee el fichero, conecta y envía un preámbulo (marca fija + token) antes de empalmar los bytes; el daemon lo compara en tiempo constante. El protocolo cliente-agente no cambia y el token nunca sale del destino. Incluye --stop para parar el daemon por PID, quitar el socket Unix y listenPrivate, y actualizar tests y README.'
Decisiones: 'Implementa §2 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. El protocolo cliente-agente sigue siendo el de [[AgentProtocol]] (HELLO primero). Contexto común en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: ['[[titan-agent instancia única y directorio de estado]]']
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:05:00+02:00
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
- Punta a punta en Linux (Docker o `nocendland-petit`) con el binario real.

## Criterios de finalización

- Sin sockets Unix en el agente; front y daemon hablan por TCP de loopback con
  token en Linux y Windows (el desacople en Windows llega con la subtarea 4:
  aquí basta con que funcione mientras la sesión sigue abierta).
- Tests verdes en Windows local y Linux (Docker, `-race`), punta a punta en
  Linux.
- `agent/README.md` actualizado (modelo de ejecución y ficheros de estado).
