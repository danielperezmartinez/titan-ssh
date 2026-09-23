---
Nombre: Resiliencia nivel 3 agente propio en el destino
Estado: Hecha
Resumen: 'ENTREGADO y verificado de punta a punta (host real nocendland-petit). Nivel 3 del modelo de resiliencia (ADR-0003): agente propio de titan-ssh en el destino para persistencia total. Diseño en [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (opción B: ayudante sobre el canal exec de sshj; binario nativo Go, no MINA/JVM → anula esa reserva de ADR-0004). Piezas: protocolo por tramas AgentProtocol (cliente + agente Go), SshExecChannel (canal exec sshj), agente agent/ (PTY real, daemon UDS+front que sobrevive a la desconexión, tee vivo, replay al reconectar), AgentInstaller (instala por exec+checksum, idempotente, espacio de usuario), AgentTransport (transporte cliente con dedupe de replay) integrado en SessionTab tras un AgentDeployer, y empaquetado de binarios multi-arch como recursos (task Gradle buildAgentBinaries) cableado en la app (createAgentDeployer). El usuario elige "agente" en el editor de sesión y todo es automático (nada que instalar a mano en el host). Follow-ups menores: scripts de inicio sobre el agente y capar buffer por ACK.'
Decisiones: 'Sigue [[ADR-0003 Modelo de resiliencia por niveles]] y su diseño [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (que matiza [[ADR-0004 Librería SSH]]: la reserva de MINA para el agente queda anulada a favor de un binario nativo Go; sshj sigue vigente en el cliente). Se apoya en los niveles 1-2 de [[Resiliencia de sesión ante microcortes de red]]. Superficie catalogada en [[AgentProtocol]].'
Bloqueada: []
Fecha de creación: 2026-09-19T16:45:00+02:00
Última modificación: 2026-09-23T22:10:00+02:00
---

# Resiliencia nivel 3: agente propio en el destino

## Objetivo

Persistencia total de la sesión ("no perder nunca el trabajo") mediante un
componente propio de titan-ssh en la máquina remota que desacopla la sesión del
transporte: mantiene los PTYs vivos y, al reconectar, reproduce el buffer para que
el cliente recupere exactamente el estado, incluso tras una caída total.

## Criterios de finalización (borrador)

- Agente ligero instalable en el destino con PTYs persistentes.
- Buffer/replay del output al reconectar; recuperación del proceso en primer plano.
- Protocolo cliente↔agente.
- Diseño de seguridad del agente (identidad, superficie de ataque,
  instalación/actualización) tratado explícitamente.

## Diseño resuelto (2026-09-19) → ADR-0008

La condición de arranque de esta tarea (una ADR de diseño propia antes de
implementar) está **cumplida**: ver
[[ADR-0008 Diseño del agente de resiliencia nivel 3]]. Resuelve las seis preguntas
abiertas de abajo:

1. **Transporte:** opción **B** (ayudante sobre el canal `exec` de sshj), no
   servidor SSH propio (opción A descartada).
2. **Runtime:** **binario nativo estático en Go**, no MINA/JVM → **anula la
   reserva de MINA de [[ADR-0004 Librería SSH]]** para el agente (sshj sigue en el
   cliente; ADR-0004 no se marca `Reemplazada`, solo se le añade la nota de
   enlace).
3. **Distribución:** subida por SFTP con detección `uname`, ruta versionada y
   verificación de **checksum**; solo espacio de usuario.
4. **Buffer/replay:** ring buffer por sesión con offsets de byte; `HELLO(offset)`
   → replay desde ese offset o desde `tail` si expiró.
5. **Ciclo de vida:** daemoniza (double-fork/setsid), reengancha por id, GC por
   TTL, capa por bytes.
6. **Seguridad:** corre como el usuario ya autenticado, sin nueva superficie de
   auth; buffer en memoria (o fichero `0600`); integridad por checksum.

La investigación previa que sustentó estas decisiones se conserva abajo.

## Esqueleto entregado (2026-09-19)

Alcance acordado con el usuario para esta sesión: **ADR + esqueleto del agente**.
Entregado y verificado:

- **`AgentProtocol`** (`shared/.../terminal/AgentProtocol.kt`, `commonMain`):
  códec puro del protocolo por tramas (encode + `FrameDecoder` tolerante a troceo),
  fuente de verdad del formato de cable. Catalogado en [[AgentProtocol]].
- **`titan-agent`** (`agent/`, Go): módulo con el protocolo espejo
  (`internal/protocol`), el ring buffer con offsets (`internal/buffer`), el
  bosquejo de registro/sesión/proxy (`internal/session`) y el entrypoint
  (`cmd/titan-agent`). PTY real y daemonización marcados `TODO(level3)`. No se
  compila en esta máquina (sin toolchain Go); los tests Go quedan escritos para
  cuando la haya.

## Subtareas de implementación

Estado (todas siguen la ADR-0008):

- ✅ **`Hecha`** [[Nivel 3 canal exec en el motor SSH]] — `SshExecChannel` +
  `SshSession.exec` (impl sshj), verificado contra host real.
- ✅ **`Hecha`** [[titan-agent PTY y daemonización]] — binario Go funcional (PTY
  real, daemon UDS + front, tee vivo, reenganche), verificado de punta a punta en
  `nocendland-petit` (crea PTY, teea el prompt, INPUT llega, replay al reconectar).
- ✅ **`Hecha`** [[titan-agent distribución multi-arch e instalación]] —
  instalación por exec+checksum (idempotente) **y** empaquetado: el task Gradle
  `buildAgentBinaries` cross-compila a recursos JVM `/agent/`, `AgentBinaries` los
  carga por classloader. Verificado (ELF en classpath + instalación en host).
- ✅ **`Hecha`** [[Nivel 3 transporte cliente e integración de resiliencia]] —
  `AgentTransport` sobre `AgentProtocol` + el canal exec (dedupe/replay), integrado
  en `SessionTab` tras un `AgentDeployer` opcional (degrada limpio sin él).
  Verificado headless y de punta a punta contra host real (echo + replay al
  reconectar). Superficie: [[AgentTransport]].

**Cableado de app completo:** `createAgentDeployer()` (expect/actual) se inyecta en
el `SessionManager` desde `AppShell`, y el editor de sesión ya ofrece elegir
**"agente"**. Una sesión con `ResilienceLevel.AGENT` instala y conduce el agente
automáticamente; sin binario para la arquitectura del destino (o si algo falla),
degrada al nivel 2/1.

## Follow-ups menores (no bloquean el nivel 3)

- [[Scripts de inicio por sesión sobre el agente]] — hoy no se invoca
  `StartScriptAutomation` en la ruta del agente; el `$SHELL -il` del agente sí
  corre los rc del usuario.
- [[Verificar empaquetado del agente en APK Android]] — mecanismo idéntico al de
  escritorio (ya verificado); no ejecutado on-device esta sesión.
- Capar el ring buffer del agente por el mínimo de los `ACK` (hoy capa por bytes).
  Mejora interna del agente Go; sin tarea propia por ahora.

## Investigación previa (2026-09-19) — para no re-investigar

Recopilado al cerrar el nivel 1. Este nivel **no es una pasada de código**: es una
arquitectura que necesita su **propia ADR de diseño** antes de implementar. Lo
averiguado (ya consolidado en [[ADR-0008 Diseño del agente de resiliencia nivel 3]]):

### Por qué no basta con niveles 1-2

- **Nivel 1 (reconexión de cliente, ya `Hecho`):** ante una caída total del
  transporte, el proceso en primer plano del shell remoto muere (SIGHUP); al
  reconectar se abre un shell nuevo. Conserva scrollback del **cliente**, no el
  proceso remoto.
- **Nivel 2 (auto-tmux/screen):** conserva el proceso, pero depende de que exista
  el multiplexor en el destino, usa la **pantalla alterna** (requiere soporte en
  el emulador, ver más abajo) y su "replay" es lo que tmux **redibuja** de la
  pantalla actual, no el historial completo de salida.
- **Nivel 3:** buffering/replay controlado por titan, sin depender de herramientas
  del destino, con recuperación exacta del estado.

### Bifurcación de diseño clave: transporte del agente

- **Opción A — agente como servidor SSH propio** (Apache MINA SSHD, reservado en
  [[ADR-0004 Librería SSH]] por ser la única lib Java con cliente **y** servidor):
  el agente escucha en su puerto, con su host key y su autenticación. Mucha más
  superficie de ataque y fricción. **Desaconsejada de entrada.**
- **Opción B — agente como proceso ayudante sobre el canal SSH existente**
  (recomendada a evaluar primero): el cliente conecta por SSH normal (sshj) y hace
  `exec` de un binario `titan-agent` en el destino; se habla un **protocolo por
  tramas sobre el stdio de ese canal exec**. Reutiliza el transporte y la auth de
  SSH (sin puerto nuevo, sin re-implementar auth, mínima superficie). El agente
  daemoniza para sobrevivir a la desconexión del cliente y mantiene vivos los PTY.

### Tensión importante: MINA SSHD (JVM) vs binario nativo ligero

- ADR-0004 **reserva MINA SSHD** para este agente, pero MINA es JVM → un agente
  MINA exige **Java en el destino** (pesado), lo que choca con "agente **ligero**,
  fácil de instalar".
- Si se adopta la **opción B**, probablemente **no haga falta MINA** (no hay
  servidor SSH que implementar): un **binario nativo estático pequeño** (Go/Rust)
  que hable el protocolo por tramas sobre el canal exec encaja mucho mejor con
  "ligero" y con la distribución multi-arch. **Esto revisaría la reserva de MINA
  de ADR-0004** — anotar en la ADR de diseño futura (posible `Reemplazada`/matiz de
  ADR-0004).

### Bosquejo de arquitectura (opción B, a validar en la ADR)

- `titan-agent`: binario pequeño instalado en el destino (p. ej.
  `~/.local/share/titan-ssh/agent`).
- Cliente conecta por SSH (sshj) y hace `exec titan-agent --session <id>`.
- Primer uso de un `id`: el agente crea un PTY + shell (la sesión "real"); en
  adelante hace de proxy (entrada cliente→PTY, salida PTY→cliente) manteniendo un
  **ring buffer** de salida (y scrollback hasta un tope) para replay-desde-offset.
- El agente **sobrevive** a la desconexión del cliente (double-fork / líder de
  sesión / grupo de procesos propio; el master del PTY sigue abierto). Al
  reconectar, el cliente re-`exec`, el agente re-engancha al PTY existente y hace
  **replay** desde el último offset confirmado por el cliente.
- **Protocolo** (tramas con prefijo de longitud sobre el stdio del canal exec):
  `HELLO(session id, last offset)`, `DATA(pty output)`, `INPUT(client→pty)`,
  `RESIZE(cols,rows)`, `REPLAY_FROM(offset)`, `ACK(offset)`, `BYE`. Binario, mínimo.
- **Ciclo de vida/GC:** el agente indexa sesiones por id, expira las ociosas por
  TTL, capa el buffer por bytes.

### Distribución / instalación

- El binario debe llegar al destino: (1) el cliente lo **sube por SFTP/scp** en el
  primer uso (requiere binario por arch/OS; detectar `uname -m`/`uname -s`),
  (2) instalación manual, (3) script de bootstrap.
- Un **estático nativo** (Go/Rust) simplifica la distribución multi-arch frente a
  un agente JVM. Verificar checksum del binario subido (integridad). Solo
  espacio de usuario (sin root).

### Seguridad

- **Opción B**: el agente corre como el usuario ya autenticado por SSH, sobre un
  canal ya autenticado → **sin nueva superficie de auth**.
- **Opción A** exigiría host key + auth + puerto propios → superficie mucho mayor;
  desaconsejada.
- El buffer puede contener salida sensible: mantenerlo en memoria o en fichero
  `0600`; considerar no persistir a disco o cifrarlo.
- Integridad del binario instalado; mínimo privilegio (usuario, no root).

### Interacción con el emulador

- El replay de bytes crudos del PTY al emulador del cliente es lo que ya hace el
  nivel 1 por conexión; el nivel 3 reproduce el **buffer del agente** en vez de un
  shell nuevo.
- Las apps de pantalla completa siguen necesitando **pantalla alterna** en el
  [[TerminalEmulator]] (prerrequisito compartido con el nivel 2,
  [[Resiliencia nivel 2 auto-tmux o screen]]).

### Config

- `ResilienceLevel.AGENT` ya existe en el modelo; una sesión que lo elija dispara
  la ruta del agente.

### Preguntas abiertas a resolver en la ADR de diseño

1. Transporte: servidor SSH propio (MINA, opción A) vs ayudante sobre canal exec
   (opción B, evaluar primero).
2. Runtime/lenguaje del agente: JVM+MINA (necesita Java remoto) vs binario nativo
   estático (Go/Rust). Impacta "ligero" y distribución; puede **superar la reserva
   de MINA de [[ADR-0004 Librería SSH]]**.
3. Distribución/instalación y multi-arch.
4. Semántica de buffer/replay (offsets, ACKs, topes, scrollback vs pantalla).
5. Ciclo de vida del agente (daemonizar, TTL, GC, ¿múltiples clientes?).
6. Seguridad (identidad, buffer en disco, integridad).

## Notas

- Interacción con `ResilienceLevel.AGENT` del modelo de config.
- Es la pieza de mayor alcance y fricción del modelo; **requiere su propia ADR de
  diseño e (probablemente) subtareas** antes de implementar.

## Verificación

El **mecanismo completo del nivel 3** está implementado y verificado (headless +
punta a punta contra el host real [[ssh-test-host]]). La tarea sigue **En curso**
solo porque `AGENT` aún no es alcanzable desde la UI (falta empaquetar binarios y
cablear el deployer).

- **Códec** (`AgentProtocolTest`): 7/7 — round-trip de cada trama, `FrameDecoder`
  tolerante a troceo, rechazo de tipo/longitud inválidos.
- **Agente Go** (`go test ./...`, `go vet`): protocolo, ring buffer y sesión
  (proxy con PTY fake) verdes; cross-compila a linux amd64/arm64 y darwin/arm64.
- **Agente en host real**: `HELLO`→`HELLO_OK`+`DATA` con el prompt real (PTY
  creado y teeado), `INPUT` ejecutado por el shell, y **reconexión**: el daemon
  (setsid) sobrevive y reproduce el historial desde offset 0.
- **Instalador** (`AgentInstallTest` + `AgentInstallerIntegrationTest` en host):
  detección `uname`, subida por exec+`head -c`, checksum SHA-256, idempotencia.
- **Transporte cliente** (`AgentTransportTest` + `AgentTransportIntegrationTest`
  en host): `AgentTransport` conduce el agente, dedupe de replay, y al reconectar
  recupera el historial.
- **Gate**: ambos targets (`:shared:compileAndroidMain` + `compileKotlinDesktop`)
  y la suite de escritorio completa en verde; Go verde.

## Resultado

Nivel 3 construido y verificado (diseño en
[[ADR-0008 Diseño del agente de resiliencia nivel 3]]):

- **Cliente (KMP)**: `AgentProtocol` (códec, [[AgentProtocol]]), `SshExecChannel`
  + `SshSession.exec` (sshj), `AgentInstaller`/`AgentDeployer` (instalación por
  exec+checksum), `AgentTransport` ([[AgentTransport]]), integrado en `SessionTab`
  tras un `AgentDeployer` opcional (degrada al nivel 2/1 sin él → cero regresión).
- **Agente del destino** (`agent/`, Go): binario funcional — PTY real, daemon UDS
  + front, tee vivo, reenganche con replay; cross-compila multi-arch.
- **ADR-0004 matizada** (reserva de MINA anulada para el agente).
- **Subtareas**: exec (Hecha), PTY/daemon (Hecha), transporte/integración (Hecha),
  distribución (En curso: falta empaquetar binarios).

**Pendiente para cerrar la tarea**: empaquetar los binarios multi-arch como
recursos de la app y cablear un `AgentDeployer` al arrancar, para que
`ResilienceLevel.AGENT` sea seleccionable y funcione desde la UI.

**Rediseño portable (2026-09-23)**: el agente entregado solo funciona en destinos
Unix y pierde sesiones cuando systemd borra `/run/user`. El rediseño para Windows,
macOS, BSD y cualquier Linux está en
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]] (`Aceptada`
2026-09-23, tras [[Experimento supervivencia de procesos en Win32-OpenSSH]]). Implementación en
[[Nivel 3 portable a todos los destinos]].
