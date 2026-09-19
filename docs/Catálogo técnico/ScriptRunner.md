---
Nombre: "ScriptRunner"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/terminal/ScriptRunner.kt"
Entrada pública: "im.gar.titanssh.terminal"
Resumen: "Motor de ejecución de los scripts de inicio de sesión al conectar. ScriptRunner (puro, testeable) recibe un ShellIo (send + tee de salida) y, sobre la shell viva, envía el cd inicial y los scripts en orden honrando ${ref} (solo secretos; el resto de ${...} lo expande la shell remota), export de envVars, delay, expect (espera un patrón antes de enviar), waitForCompletion con centinela printf que arrastra $? y timeout, y onFailure CONTINUE/ABORT; devuelve un ScriptOutcome por unidad. StartScriptAutomation implementa el seam ShellAutomation: selecciona las fases de conexión (ON_SHELL_START → POST_INIT, habilitadas, en orden) y resuelve secretos del SecretStore solo en runtime. silent no se suprime aún (limitación de PTY compartida); ON_RECONNECT/PRE_CONNECT_LOCAL fuera de alcance."
Última modificación: 2026-09-19T13:20:00+02:00
---

# ScriptRunner

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[ScriptRunner.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/ScriptRunner.kt),
[StartScriptAutomation.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/StartScriptAutomation.kt)
y el seam
[ShellAutomation.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/ShellAutomation.kt)
(`ShellAutomation`, `ShellIo`).

Contrato y colaboradores:

- **Seam**: `ShellAutomation.onShellReady(io, resolved)` se ejecuta cuando la shell
  está viva, en paralelo al pintado. `ShellIo` da `send(text)` y `output`
  (`Flow<String>`), el **tee difundido** de [[SessionManager]] — no el `output` de
  un solo consumidor de [[SshConnector]], que ya drena el emulador.
- **Motor**: `ScriptRunner.run(scripts, initialDirectory)` envía el `cd` inicial y
  luego los scripts en el orden recibido. Atributos v1 soportados: `${ref}`
  (sustitución **solo** de secretos; el resto se deja a la shell remota), `export`
  de `envVars`, `delaySeconds`, `expectPattern`, `waitForCompletion` +
  `timeoutSeconds` (centinela `printf` con `$?`), `onFailure` (CONTINUE/ABORT).
- **Secretos**: `StartScriptAutomation` los lee del [[SecretStore]] solo en tiempo
  de ejecución; nunca vuelven a la config (ADR-0001). Un ref ausente salta el
  script (`ScriptOutcome` = SKIPPED).
- **Fases**: selecciona `ON_SHELL_START` → `POST_INIT` con `Session.scriptsFor`.
  `ON_RECONNECT` (y `ReconnectBehavior`) lo aporta
  [[Resiliencia de sesión ante microcortes de red]]; `PRE_CONNECT_LOCAL` no tiene
  ejecutor local aún; `ON_DEMAND` son los snippets manuales.

Limitación: `silent` no es aplicable sobre una PTY compartida (la remota hace eco);
se acepta pero no se suprime. Los comandos `waitForCompletion` deben volver al
prompt (no interactivos de larga duración).

Entregado en [[Scripts de inicio por sesión]]; consumido por [[SessionManager]].
