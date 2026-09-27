---
Nombre: "ScriptRunner"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/ScriptRunner.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Motor de ejecución de los scripts de inicio de sesión al conectar y al reconectar, más el envoltorio en multiplexor (nivel 2). ScriptRunner (puro, testeable) recibe un ShellIo (send + tee de salida) y, sobre la shell viva, envía el cd inicial y los scripts en orden honrando ${ref} (solo secretos; el resto de ${...} lo expande la shell remota), export de envVars, delay, expect, waitForCompletion con centinela printf que arrastra $? y timeout, y onFailure CONTINUE/ABORT; devuelve un ScriptOutcome por unidad. StartScriptAutomation implementa el seam ShellAutomation: onShellReady selecciona las fases de conexión (ON_SHELL_START → POST_INIT); onReconnected replica según el ReconnectBehavior (NONE/RESTORE_CD_ONLY/RERUN_ALL); onAgentSessionCreated (nivel 3) corre la cadena de conexión cuando titan-agent crea un PTY nuevo, o el replay del ReconnectBehavior si ese PTY sustituye a uno perdido en un corte, sin multiplexor y nunca al reengancharse a un PTY vivo; runOnDemand envía un script elegido en el menú de la pestaña sin esperar a que termine (sin centinela), respetando delay, expect, envVars y secretos. ShellIo envía por el shell o, en nivel 3, por tramas INPUT. Nivel 2: si resilienceLevel>=AUTO_MULTIPLEXER y hay tmux/screen, TerminalMultiplexer detecta y hace attach-or-create de una sesión titan-<id> (re-engancha sin reejecutar si ya existía; degrada al nivel 1 si no hay multiplexor). Resuelve secretos del SecretStore solo en runtime. silent no se suprime aún; PRE_CONNECT_LOCAL fuera de alcance."
Última modificación: 2026-09-27T17:30:00+02:00
---

# ScriptRunner

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[ScriptRunner.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/ScriptRunner.kt),
[StartScriptAutomation.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/StartScriptAutomation.kt),
el seam
[ShellAutomation.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/ShellAutomation.kt)
(`ShellAutomation`, `ShellIo`) y el multiplexor
[TerminalMultiplexer.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalMultiplexer.kt).

Contrato y colaboradores:

- **Seam**: `ShellAutomation` tiene `onShellReady(io, resolved)` (al conectar) y
  `onReconnected(io, resolved)` (tras reconectar; por defecto no-op), ambos en
  paralelo al pintado. `ShellIo` da `send(text)` y `output` (`Flow<String>`), el
  **tee difundido** de [[SessionManager]] — no el `output` de un solo consumidor
  de [[SshConnector]], que ya drena el emulador.
- **Motor**: `ScriptRunner.run(scripts, initialDirectory)` envía el `cd` inicial y
  luego los scripts en el orden recibido. Atributos v1 soportados: `${ref}`
  (sustitución **solo** de secretos; el resto se deja a la shell remota), `export`
  de `envVars`, `delaySeconds`, `expectPattern`, `waitForCompletion` +
  `timeoutSeconds` (centinela `printf` con `$?`), `onFailure` (CONTINUE/ABORT).
- **Secretos**: `StartScriptAutomation` los lee del [[SecretStore]] solo en tiempo
  de ejecución; nunca vuelven a la config (ADR-0001). Un ref ausente salta el
  script (`ScriptOutcome` = SKIPPED).
- **Readiness**: `StartScriptAutomation` espera la primera salida de la shell (el
  prompt) antes de enviar nada; una PTY remota descarta la entrada escrita antes
  de que la shell empiece a leer stdin, así que un primer comando ansioso se
  perdería. Ver [[ptty-drops-early-input]].
- **Fases al conectar**: `onShellReady` selecciona `ON_SHELL_START` → `POST_INIT`
  con `Session.scriptsFor`. `PRE_CONNECT_LOCAL` no tiene ejecutor local aún;
  `ON_DEMAND` no corren al conectar: se lanzan desde el menú de scripts de la
  pestaña (`SessionTab.runScript` → `runOnDemand`), junto con la biblioteca
  ([[ADR-0013 Biblioteca de scripts unificada con los snippets]]).
- **Al reconectar**: `onReconnected` mira `Session.effectiveReconnectBehavior()`
  (el `reconnectBehavior` del primer script `ON_RECONNECT` habilitado; sin ninguno,
  por defecto `RESTORE_CD_ONLY`) y replica: `NONE` nada, `RESTORE_CD_ONLY` solo el
  `cd` inicial, `RERUN_ALL` la cadena completa (`cd` + `ON_SHELL_START` +
  `POST_INIT` + los scripts `ON_RECONNECT`). Aportado por
  [[Resiliencia de sesión ante microcortes de red]] (ADR-0003 nivel 1).
- **Multiplexor (nivel 2)**: si `Session.resilienceLevel >= AUTO_MULTIPLEXER` y el
  destino tiene tmux/screen, `StartScriptAutomation` envuelve la shell con
  `TerminalMultiplexer` (`detect` → `sessionExists` → `enter` con
  `exec tmux new-session -A -s titan-<sessionId>` / `screen -xRR`). Al conectar: si
  la sesión del multiplexor ya existía, **re-engancha y NO reejecuta** los scripts;
  si es nueva, entra y corre la cadena. Al reconectar: si el multiplexor sobrevivió,
  re-engancha sin replay (el proceso remoto siguió vivo); si se perdió, cae al
  replay del nivel 1. Sin multiplexor, degrada al nivel 1. `TerminalMultiplexer` es
  puro (sondas por centinela como el runner) y vive en el mismo paquete. Requiere
  pantalla alterna en el [[TerminalEmulator]] (añadida para este nivel). Aportado
  por [[Resiliencia nivel 2 auto-tmux o screen]].
- **Agente (nivel 3)**: con `ResilienceLevel.AGENT` y el agente instalado, la
  pestaña no usa los hooks de shell sino `onAgentSessionCreated(io, resolved,
  afterDrop)`, y solo cuando `titan-agent` crea un PTY nuevo: al reengancharse a
  uno vivo no se ejecuta nada, porque el agente reproduce la sesión tal como
  estaba. En la primera conexión corre la cadena de conexión (`cd` +
  `ON_SHELL_START` + `POST_INIT`). Si el PTY nuevo sustituye a uno perdido
  durante un corte (`afterDrop`, p. ej. el destino se reinició), aplica el
  replay del `ReconnectBehavior` como en el nivel 1. Nunca envuelve en
  tmux/screen: el PTY del agente ya sobrevive a los cortes. El `ShellIo` de esta
  ruta envía por tramas `INPUT` y su `output` es el mismo tee, alimentado con
  los `DATA` del agente. Aportado por
  [[Scripts de inicio por sesión sobre el agente]].

Limitación: `silent` no es aplicable sobre una PTY compartida (la remota hace eco);
se acepta pero no se suprime. Los comandos `waitForCompletion` deben volver al
prompt (no interactivos de larga duración).

Entregado en [[Scripts de inicio por sesión]] (reconexión en
[[Resiliencia de sesión ante microcortes de red]]); consumido por [[SessionManager]].
