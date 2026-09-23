---
Nombre: Diseño del agente de resiliencia nivel 3
Número: 8
Estado: Aceptada
Resumen: 'Diseño del agente propio del nivel 3 (ADR-0003): un proceso ayudante que titan lanza con `exec` sobre el canal SSH ya autenticado (opción B, no un servidor SSH propio) y con el que habla un protocolo binario por tramas sobre el stdio de ese canal. El agente es un binario nativo estático (Go), no MINA/JVM: esto revisa y anula la reserva de MINA de ADR-0004 para el agente (el cliente sigue en sshj). El agente mantiene un PTY vivo por sesión, sobrevive a la desconexión del cliente (daemoniza), guarda un ring buffer de salida y hace replay-desde-offset al reconectar. Distribución por SFTP con detección de arch/OS y verificación de checksum; solo espacio de usuario. Buffer en memoria (fichero 0600 opcional). Trama y offsets definidos; el códec de tramas se implementa en cliente (AgentProtocol) como pieza verificable de arranque.'
Decisión: 'Adoptar la opción B (agente ayudante sobre `exec` del canal SSH existente, sin puerto ni auth propios), implementado como binario nativo estático en Go que habla un protocolo binario por tramas (HELLO/HELLO_OK/DATA/INPUT/RESIZE/REPLAY_FROM/ACK/BYE) con offsets de byte para replay. Esto anula la reserva de MINA SSHD para el agente hecha en ADR-0004 (que sigue vigente para el cliente sshj).'
Consecuencias: 'Reutiliza el transporte y la auth de SSH (mínima superficie de ataque, sin puerto nuevo); el agente ligero no exige Java en el destino y la distribución multi-arch se simplifica con un estático. A cambio, titan asume el mantenimiento de un componente en otro lenguaje (Go) y su ciclo de vida/distribución (build multi-arch, subida, verificación de integridad, GC de sesiones). Requiere añadir soporte de canal `exec` al motor SSH (hoy solo hay `openShell`).'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-19T18:10:00+02:00
Última modificación: 2026-09-23T21:30:00+02:00
---

# ADR-0008 · Diseño del agente de resiliencia nivel 3

> **Actualización (ADR-0009, 2026-09-23): sustituida en parte.**
> [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] sustituye el §5
> (destinos compilados), el §6 (punto de encuentro por socket Unix y
> daemonización solo Unix) y la elección de `creack/pty`, para que el nivel 3
> funcione también en Windows, macOS, BSD y cualquier Linux. El resto de esta ADR
> sigue vigente: opción B sobre `exec`, Go, protocolo por tramas, semántica de
> buffer y replay, e instalación por SFTP con checksum.

## Contexto

[[ADR-0003 Modelo de resiliencia por niveles]] fija tres niveles de resiliencia.
Los niveles 1 (reconexión de cliente) y 2 (auto-tmux/screen) están entregados
(ver [[Resiliencia de sesión ante microcortes de red]] y
[[Resiliencia nivel 2 auto-tmux o screen]]). El **nivel 3** —persistencia total
mediante un componente propio de titan en el destino— quedó explícitamente
aplazado en su tarea [[Resiliencia nivel 3 agente propio en el destino]] con la
condición de **resolver su diseño en una ADR antes de implementar**, por ser la
pieza de mayor alcance, fricción y superficie de seguridad del modelo.

Esta ADR resuelve las seis preguntas abiertas que la investigación previa dejó
anotadas en la tarea. No es una decisión de stack nueva: refina ADR-0003 y matiza
[[ADR-0004 Librería SSH]] en el punto del agente.

### Por qué no basta con los niveles 1-2

- **Nivel 1**: ante una caída total del transporte, el proceso en primer plano
  del shell remoto muere (SIGHUP); al reconectar se abre un shell nuevo. Conserva
  el scrollback del **cliente**, no el proceso remoto.
- **Nivel 2**: conserva el proceso, pero **depende de que el destino tenga**
  tmux/screen, usa la pantalla alterna y su "replay" es lo que el multiplexor
  **redibuja** de la pantalla actual, no el historial de salida.
- **Nivel 3**: buffering/replay controlado por titan, sin depender de
  herramientas del destino, con recuperación exacta del estado.

## Decisión

### 1. Transporte del agente — opción B (ayudante sobre `exec`)

El cliente conecta por SSH normal (sshj, ADR-0004) y hace **`exec` de un binario
`titan-agent`** en el destino. Cliente y agente hablan un **protocolo binario por
tramas sobre el stdio de ese canal exec**.

Se descarta la **opción A** (el agente como servidor SSH propio con su puerto,
host key y auth): multiplica la superficie de ataque y la fricción de instalación
sin aportar nada que la opción B no dé. La opción B **reutiliza el transporte y la
autenticación de SSH ya establecidos** — sin puerto nuevo, sin re-implementar
auth, superficie mínima.

### 2. Runtime del agente — binario nativo estático (Go), no MINA/JVM

El agente es un **binario nativo estático en Go**, no un proceso JVM con Apache
MINA SSHD.

Motivo: ADR-0004 **reservó MINA** para este agente por ser la única lib Java con
cliente y servidor, pero con la opción B **no hay servidor SSH que implementar**
(el transporte y la auth los pone sshj en el cliente). Un agente MINA solo
añadiría el requisito de **tener Java en el destino** (pesado), que choca de
frente con el objetivo de un **agente ligero y fácil de instalar**. Un estático de
Go no tiene dependencias de runtime en el destino y su cross-compile multi-arch es
trivial (`GOOS`/`GOARCH`), lo que además simplifica la distribución (punto 5).

Se elige **Go** sobre Rust por PTY y daemonización más directas (`os/exec`,
`syscall`, `creack/pty`) y una toolchain de cross-compile más cómoda, a cambio de
binarios algo mayores (aceptable para un helper).

**Esta decisión anula la reserva de MINA de ADR-0004 en el punto del agente.** El
resto de ADR-0004 (sshj como cliente) **sigue vigente**; por eso ADR-0004 no se
marca `Reemplazada`, solo se le añade una nota de enlace a esta ADR (una decisión
por nota; aquí solo cae la reserva del agente).

### 3. Protocolo por tramas

Binario, con **prefijo de longitud**, sobre el stdio del canal exec. Es la fuente
de verdad del formato de cable; el códec se implementa primero en el cliente
(`AgentProtocol`, ver "Estado de implementación").

Formato de trama:

```
+--------+------------------+------------------+
| type   | length (u32 BE)  | payload[length]  |
| 1 byte | 4 bytes          | length bytes     |
+--------+------------------+------------------+
```

Tipos de trama y payload:

| Tipo         | u8 | Dirección       | Payload |
|--------------|----|-----------------|---------|
| `HELLO`      | 1  | cliente → agente | sessionId (u16 len + utf8) · lastOffset (u64 BE) · cols (u16) · rows (u16) |
| `HELLO_OK`   | 2  | agente → cliente | headOffset (u64 BE) · tailOffset (u64 BE) — rango disponible en el buffer |
| `DATA`       | 3  | agente → cliente | offset (u64 BE) del primer byte · bytes crudos del PTY |
| `INPUT`      | 4  | cliente → agente | bytes crudos hacia el PTY |
| `RESIZE`     | 5  | cliente → agente | cols (u16 BE) · rows (u16 BE) |
| `REPLAY_FROM`| 6  | cliente → agente | offset (u64 BE) desde el que reproducir |
| `ACK`        | 7  | cliente → agente | offset (u64 BE) consumido y persistible por el cliente |
| `BYE`        | 8  | cualquiera       | vacío |

- Los **offsets** son un contador monótono de bytes producidos por el PTY de la
  sesión desde su creación. `DATA` es autodescriptivo (lleva el offset de su
  primer byte), lo que hace robustos el `ACK` y el `REPLAY_FROM`.
- El **códec** solo (de)serializa tramas y tolera troceado del stream (un
  decodificador acumulador que emite tramas completas). La **semántica** de
  offsets/replay vive en la capa de transporte del agente (subtarea aparte).

### 4. Semántica de buffer / replay

- El agente mantiene por sesión un **ring buffer** de la salida del PTU acotado
  por bytes (`headOffset` = total producido; `tailOffset` = head − tamaño del
  buffer). Opcionalmente, un scrollback mayor con tope propio.
- En `HELLO(lastOffset)`: si `tailOffset ≤ lastOffset ≤ headOffset`, el agente
  hace **replay desde `lastOffset`**; si el cliente pide un offset ya expirado del
  buffer (o `0` en un arranque limpio), el agente responde `HELLO_OK` con el rango
  y reproduce **desde `tailOffset`**, y el cliente **resetea su emulador** a ese
  punto (equivalente a un shell nuevo, pero conservando lo que el buffer aún
  tenga).
- El cliente confirma progreso con `ACK(offset)`; el agente puede capar el buffer
  con seguridad hasta el mínimo entre los ACK de sus clientes.

### 5. Distribución / instalación y multi-arch

> Lista de destinos y detección de SO/arquitectura **sustituidas** por
> [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] (§6 y §7). La
> subida por SFTP con checksum sigue vigente.

- **Primer uso**: el cliente detecta `uname -s`/`uname -m` del destino, elige el
  binario `titan-agent` correcto (p. ej. `linux/amd64`, `linux/arm64`, `darwin/*`)
  y lo **sube por SFTP** a `~/.local/share/titan-ssh/agent` (solo espacio de
  usuario, **sin root**), con permisos `0700`.
- **Integridad**: se verifica el **checksum** (SHA-256) del binario subido antes
  de ejecutarlo; si no coincide con el esperado para esa versión/arch, se aborta y
  se degrada al nivel 2/1.
- **Versionado**: la ruta incluye la versión (`agent-<ver>-<os>-<arch>`) para
  permitir convivencia y actualización idempotente (no re-subir si el checksum ya
  está).
- Alternativas de instalación manual o script de bootstrap quedan como vías
  secundarias, no requeridas por defecto.

### 6. Ciclo de vida y seguridad del agente

> Punto de encuentro, instancia única y desacople **sustituidos** por
> [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] (§2, §3 y §5).

- **Daemonización**: tras el primer `exec`, el agente crea el PTY + shell (la
  sesión "real") y se **desacopla** del canal (double-fork / nuevo líder de
  sesión / grupo de procesos propio) para sobrevivir a la desconexión del cliente,
  manteniendo abierto el master del PTY. En adelante hace de proxy
  INPUT→PTY / PTY→DATA.
- **Reenganche**: al reconectar, el cliente re-`exec`uta `titan-agent`, que
  localiza la sesión viva por su `id`, y hace **replay** desde el offset del
  `HELLO`.
- **GC**: el agente indexa sesiones por id, expira las ociosas por **TTL** y capa
  el buffer por bytes.
- **Seguridad**: corre como el usuario ya autenticado por SSH, sobre un canal ya
  autenticado → **sin nueva superficie de auth**. El buffer puede contener salida
  sensible: se mantiene **en memoria**; si se persiste, fichero **`0600`** (o
  cifrado). Mínimo privilegio (usuario, nunca root). Integridad del binario
  verificada por checksum (punto 5).

## Alternativas consideradas

- **Opción A (servidor SSH propio con MINA)** — descartada: superficie de ataque y
  fricción muy superiores; obliga a host key + auth + puerto propios sin ventaja
  frente a B.
- **Agente MINA/JVM sobre la opción B** — descartada: exige Java en el destino,
  contradice "ligero" y complica la distribución multi-arch; sin servidor SSH que
  implementar, MINA no aporta.
- **Rust en vez de Go** — viable y con binarios menores, pero PTY/daemonización más
  artesanal y toolchain de cross-compile menos cómoda; se prefiere Go.
- **Depender de mosh** (ya descartada en ADR-0003) — exige mosh en ambos extremos y
  UDP; se prefiere control total del buffering/replay con agente propio.

## Consecuencias

- **Positivas**: persistencia total sin depender de herramientas del destino;
  mínima superficie de auth (reutiliza SSH); agente ligero sin Java; distribución
  multi-arch simple; protocolo propio con control total de buffer/replay.
- **Negativas / compromisos**: titan mantiene un componente en **otro lenguaje**
  (Go) con su build multi-arch, distribución y verificación de integridad; hay que
  **añadir soporte de canal `exec`** al motor SSH (hoy `SshSession` solo expone
  `openShell`); el ciclo de vida del daemon (TTL, GC, reenganche) es no trivial y
  se lleva en subtareas.

## Estado de implementación (esqueleto de arranque)

Esta ADR se acompaña de un **esqueleto** verificable (no una implementación
completa):

- **`AgentProtocol`** (cliente, `commonMain`): códec puro del protocolo por tramas
  (encode/decode + decodificador acumulador tolerante a troceo). Es la fuente de
  verdad del formato de cable y la pieza verificable de arranque (tests headless).
  Catalogado en [[AgentProtocol]].
- **`titan-agent`** (`agent/`, Go): esqueleto del binario del destino (parser de
  tramas espejo del códec, bosquejo de PTY/ring buffer/daemonización). No se
  compila en este entorno (sin toolchain Go); requiere `go build` multi-arch.

El resto (canal `exec` en el motor SSH, subida SFTP + checksum, daemonización real
y GC, integración en el flujo de resiliencia sustituyendo la degradación temporal
al nivel 2) se lleva en las subtareas enlazadas desde
[[Resiliencia nivel 3 agente propio en el destino]].

Ver [[ADR-0003 Modelo de resiliencia por niveles]], [[ADR-0004 Librería SSH]] y la
tarea [[Resiliencia nivel 3 agente propio en el destino]].
