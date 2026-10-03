---
Nombre: 'Autenticación mutua en el punto de encuentro del agente'
Número: 18
Estado: 'Aceptada'
Resumen: 'Sustituye en parte a ADR-0009, solo en el preámbulo de su punto 2. Los dos extremos demuestran que conocen el token sin enviarlo, con un reto y respuesta HMAC-SHA256 sobre dos nonces aleatorios, y el front no envía nada más hasta comprobar la prueba del daemon. Vale para las cuatro conexiones del punto de encuentro (sesión y control del daemon, entrada y control del ayudante de escritorio). Los daemons y ayudantes nuevos aceptan también el preámbulo anterior, para los clientes sin actualizar; un front nuevo nunca abre una sesión con él, y solo lo usa para reconocer y detener un agente de una versión anterior mientras este tiene el candado.'
Decisión: 'Autenticar en los dos sentidos el tramo front↔daemon (y front↔ayudante) con HMAC-SHA256 del token sobre las marcas y dos nonces, con marcas nuevas (versión 2); conservar el preámbulo anterior solo en el lado que escucha y, en el cliente, solo para detener o reconocer un agente anterior que tiene su candado.'
Consecuencias: 'El token ya no cruza el socket y los bytes del cliente solo llegan a un proceso que tiene el token. Un cliente nuevo no abre sesiones en un daemon de una versión anterior: lo indica con E_AGENT_OUTDATED, y hay que detenerlo (panel del agente o --stop), lo que cierra sus sesiones una vez. El ayudante de escritorio anterior se sustituye solo. El handshake añade media ida y vuelta en loopback.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-02T01:38:01+02:00
Última modificación: 2026-10-03T09:40:07+02:00
---

# ADR-0018 · Autenticación mutua en el punto de encuentro del agente

> **Nota del 2026-10-03:** el plazo para retirar el preámbulo anterior se
> amplía una beta, con la decisión intacta: lo conserva hasta `0.1.0-beta.13`
> y el build raíz no deja generar `beta.14` ni posteriores mientras siga. En
> Android 16, `beta.11` y `beta.12` no conectaban con hosts ed25519
> ([[Claves ed25519 en Android 16 con Conscrypt]]), así que los agentes no se
> pudieron actualizar desde ellas.

Sustituye en parte a
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]]: solo el
**preámbulo** de su punto 2 (cómo se autentican el front y el daemon). El
punto de encuentro sigue siendo TCP de `127.0.0.1` con un token de 32 bytes en
un fichero de estado privado. El resto de ADR-0009 sigue vigente. El ayudante
de escritorio de
[[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]] usa el mismo
mecanismo con su propio fichero y su propio token.

## Contexto

En ADR-0009 el front enviaba una marca fija y el token, y el daemon contestaba
con otra marca fija: la autenticación iba en un solo sentido (el daemon
comprobaba al front) y el token cruzaba el socket. Se quiere que cada extremo
compruebe al otro y que el secreto no viaje, como en cualquier reto y respuesta
con un secreto compartido.

## Decisión

- **Reto y respuesta en los dos sentidos**, sin enviar el token:

  ```text
  front  → daemon  marca ‖ nonceF                      (8 + 32 bytes)
  daemon → front   nonceD ‖ HMAC(token, "server" ‖ marca ‖ nonceF ‖ nonceD)
  front  → daemon  HMAC(token, "client" ‖ marca ‖ nonceF ‖ nonceD)
  ```

  - Los nonces son de 32 bytes de `crypto/rand`. Las pruebas son
    HMAC-SHA256 y se comparan con `hmac.Equal`.
  - La etiqueta de rol hace que una prueba no sirva por la otra, y la marca
    hace que no sirva para otro tipo de conexión.
  - El front no envía nada después de su prueba hasta haber comprobado la del
    daemon. Si no cuadra, cierra.
  - El daemon cierra sin decir nada si la prueba del front no cuadra, y todo
    el handshake tiene un límite de tiempo (`handshakeTimeout`, en
    `rendezvous.go`).
- **Marcas nuevas** (`…2`) para las cuatro conexiones: `TTNAGNT2` (sesión),
  `TTNACTL2` (control), `TTNADSK2` (entrada del ayudante) y `TTNADCT2`
  (control del ayudante). Abren con los mismos 40 bytes que el preámbulo
  anterior, así que un agente anterior las rechaza sin más.
- **Compatibilidad con versiones anteriores**:
  - El daemon y el ayudante siguen aceptando el preámbulo anterior
    (`TTNAGNT1`, `TTNACTL1`, `TTNADSK1`, `TTNADCT1`), para que un cliente sin
    actualizar siga funcionando.
  - Un front nuevo **nunca** abre una sesión ni envía entrada con el
    preámbulo anterior.
  - Solo lo usa para reconocer y detener un agente anterior (`--status`,
    `--stop`, y el ayudante de otra versión al conectar el mouse pad), y solo
    mientras un proceso del usuario tiene el candado correspondiente, que es
    cuando el puerto del fichero sigue siendo el suyo.
  - Si al abrir una sesión el front encuentra un daemon de otra versión que no
    completa el handshake, falla con `E_AGENT_OUTDATED`. La app explica que hay
    que actualizarlo desde el panel del agente.

## Alternativas consideradas

- **Comprobar el candado antes de conectar, sin cambiar el handshake**: deja
  una carrera entre la comprobación y la conexión, y el token seguiría
  cruzando el socket. Se usa solo como condición para el preámbulo anterior.
- **Comprobar por el sistema operativo quién escucha en el puerto**
  (`/proc/net/tcp`, `GetExtendedTcpTable`): distinto en cada sistema, y en
  algunos no está disponible sin privilegios.
- **Que un front nuevo siga abriendo sesiones con el preámbulo anterior en un
  daemon anterior**: conservaría las sesiones al actualizar, pero no da
  garantías sobre con quién se habla. Descartada.
- **TLS en el tramo de loopback**: añade certificados y dependencias para algo
  que un HMAC con un secreto compartido ya resuelve.

## Consecuencias

- Positivas:
  - El token nunca cruza el socket.
  - Los bytes del cliente solo llegan a un proceso que tiene el token.
  - Los clientes sin actualizar siguen funcionando con un daemon nuevo.
- Negativas / compromisos:
  - Al actualizar, un daemon de la versión anterior que siga en marcha hay que
    detenerlo una vez desde el panel, y se cierran sus sesiones.
  - El preámbulo anterior sigue durante la transición: se retira en
    `0.1.0-beta.12` o, como muy tarde, en `0.1.0-beta.13`, con
    [[Retirar el preámbulo anterior del agente]]. El build raíz no deja
    generar versiones posteriores a `beta.12` mientras siga en el código.
  - El handshake añade media ida y vuelta en loopback.
