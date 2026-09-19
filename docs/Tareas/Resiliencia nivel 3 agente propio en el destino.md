---
Nombre: Resiliencia nivel 3 agente propio en el destino
Estado: Pendiente
Resumen: 'Nivel 3 del modelo de resiliencia (ADR-0003): componente ligero de titan-ssh instalable en la máquina remota que mantiene PTYs persistentes y hace buffer/replay al reconectar, para persistencia total ("app en ambos extremos"). APLAZADO: requiere ADR de diseño propia antes de implementar. Investigación previa volcada en la nota (bifurcación clave: agente-servidor-SSH con MINA vs ayudante sobre el canal exec existente; tensión MINA-JVM vs binario nativo ligero que revisaría ADR-0004; protocolo por tramas, distribución multi-arch, seguridad, ciclo de vida). Mejora opcional máxima sobre los niveles 1-2.'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]]; [[ADR-0004 Librería SSH]] reserva MINA SSHD para el agente, pero la investigación previa sugiere revisarlo (posible binario nativo). Se apoya en los niveles 1-2 de [[Resiliencia de sesión ante microcortes de red]].
Bloqueada: []
Fecha de creación: 2026-09-19T16:45:00+02:00
Última modificación: 2026-09-19T17:05:00+02:00
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

## Investigación previa (2026-09-19) — para no re-investigar

Recopilado al cerrar el nivel 1. Este nivel **no es una pasada de código**: es una
arquitectura que necesita su **propia ADR de diseño** antes de implementar. Lo
averiguado:

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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
