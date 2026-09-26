---
Nombre: 'Scripts de inicio por sesión sobre el agente'
Estado: 'Hecha'
Resumen: 'Las sesiones de nivel 3 (ResilienceLevel.AGENT) ya ejecutan su automatización de inicio (cd inicial, ON_SHELL_START, POST_INIT), pero solo cuando titan-agent crea un PTY nuevo. Al reengancharse a un PTY vivo (tras un microcorte o al reabrir la app) no se ejecuta nada, porque el agente reproduce la sesión tal como estaba. Para saberlo, HELLO_OK lleva un flag created, compatible con agentes y clientes anteriores. Si el PTY nuevo sustituye a uno perdido durante un corte (p. ej. el destino se reinició), se aplica el ReconnectBehavior como en el nivel 1. La automatización envía por tramas INPUT. De paso se corrigió que, tras perderse la sesión del agente, el cliente descartaba toda la salida del PTY nuevo. Verificado con tests y contra el host de pruebas real.'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (nota de actualización del 2026-09-26 sobre el flag de HELLO_OK) y reutiliza [[ScriptRunner]] / StartScriptAutomation de [[Scripts de inicio por sesión]]. Se apoya en [[AgentTransport]] y [[AgentProtocol]].'
Bloqueada: []
Fecha de creación: 2026-09-23T08:05:00+02:00
Última modificación: 2026-09-26T21:30:00+02:00
---

# Scripts de inicio por sesión sobre el agente

## Objetivo

Que una sesión con `ResilienceLevel.AGENT` corra sus scripts de inicio por sesión
igual que las de nivel 1/2, sin perder la ventaja de que al reconectar el agente
reproduce el estado (no se reejecutan).

## Contexto

En `SessionTab`, cuando la sesión enruta por `AgentTransport` (nivel 3), se
**devolvía antes** de la ruta shell + `StartScriptAutomation`. Efecto:

- El `$SHELL -il` que arranca el agente **sí** corre los rc del usuario
  (`.bashrc`/`.profile`).
- Pero la **automatización por sesión de titan** (el `cd` inicial, los scripts
  `ON_SHELL_START` y `POST_INIT`) **no** se ejecutaba.

## Criterios de finalización

- En la **primera** creación de la sesión del agente (no en reenganches), correr
  la cadena de arranque de la sesión.
- **No** reejecutar en reconexiones: el agente ya reproduce el estado; distinguir
  "sesión del agente recién creada" de "reenganche".
- Vía de entrada para la automatización: mandar el input por `INPUT` frames.
- Respetar el `ReconnectBehavior` sigue siendo cosa del nivel 1; aquí el foco es
  el arranque.

## Diseño

- **Cómo se distingue una sesión nueva.** Inferirlo en el cliente no basta. Con
  `appliedOffset == 0`, una pestaña nueva que se reengancha a un PTY vivo (al
  reabrir la app) volvería a ejecutar los scripts. Con `headOffset == 0` hay
  una carrera, porque el shell puede escribir antes del `HELLO_OK`. Como el
  daemon ya lo sabía (`Registry.AttachOrCreate` devuelve `created`), ahora lo
  comunica: `HELLO_OK` lleva un byte final de flags y el bit 0 es `created`
  (nota en ADR-0008).
- **Compatibilidad.** Un cliente anterior ignora el byte. Un agente anterior no
  lo envía, y el cliente lo decodifica como `created = null`. En ese caso cuenta
  como nueva la sesión que aún no ha producido nada (`headOffset == 0`). Si se
  equivoca, es hacia el lado seguro: no ejecuta los scripts, pero nunca los
  repite. Esto importa porque el socket del daemon no depende de la versión, y
  tras actualizar la app puede seguir vivo el daemon anterior hasta que se
  reinicie.
- **Hook.** `ShellAutomation.onAgentSessionCreated(io, resolved, afterDrop)`.
  `StartScriptAutomation` espera al prompt y ejecuta la cadena de conexión, sin
  multiplexor: el PTY del agente ya sobrevive a los cortes. Si `afterDrop` es
  verdadero (el PTY nuevo sustituye a uno perdido mientras la pestaña estaba
  fuera, p. ej. porque el destino se reinició), aplica el replay del
  `ReconnectBehavior`, igual que el nivel 2 cuando pierde su sesión de tmux.
- **E/S.** `ShellIo` admite una función de envío: en la ruta del agente es
  `sendInput` (tramas `INPUT`). Su `output` es el mismo tee de la ruta shell,
  alimentado con los `DATA` del agente, así que `awaitReady` y los centinelas
  de `ScriptRunner` funcionan igual. `AgentTransport.onAttached` llega antes de
  cualquier `DATA` y `SessionTab` lanza la automatización sin bloquear al
  lector.
- **Fallo corregido de paso.** Cuando la sesión del agente se perdía (reinicio
  del destino o GC del agente), el cliente seguía pidiendo su offset antiguo y
  el PTY nuevo empezaba en 0: toda su salida se descartaba como "ya aplicada" y
  la pestaña se quedaba muda. Ahora el agente reproduce desde 0 si la sesión es
  nueva, y el cliente pone `appliedOffset` a 0 si es nueva o si el head del
  agente va por detrás.

## Verificación

- **Go** (`go test ./...` en Windows y `go test -race ./...` en `golang:1.27`):
  ida y vuelta del flag, `HELLO_OK` de 16 bytes decodificado como no creada y
  `Handle` reflejando `created`.
- **Kotlin** (`:shared:desktopTest`, más `:shared:compileAndroidMain` y
  `:androidApp:compileDebugKotlin`):
  - `AgentProtocolTest`: flag en ida y vuelta, payload antiguo como
    `created = null` y byte igual al del agente Go.
  - `AgentTransportTest`: `onAttached` con y sin flag, y reinicio del offset
    heredado.
  - `ScriptRunnerTest`: `onAgentSessionCreated` corre `cd` + `ON_SHELL_START` +
    `POST_INIT` sin sondear tmux, y tras un corte aplica `RESTORE_CD_ONLY`.
  - `SessionTabAgentAutomationTest`, nuevo, con fakes de SSH y del agente:
    sesión nueva → hook y entrada por `INPUT`; reenganche → nada; tras un
    corte, reenganche → nada, y PTY nuevo → hook con `afterDrop = true`.
- **Host real** ([[ssh-test-host]], agente `linux/amd64` compilado de este
  cambio), en `AgentTransportIntegrationTest`:
  - El primer attach llega con `created = true` y el reenganche con `false`.
  - Caso nuevo `session_tab_runs_start_scripts_only_on_a_fresh_agent_session`:
    una pestaña de nivel 3 hace `cd /tmp` y ejecuta el script una vez (un
    fichero de conteo queda con 1 línea). Una segunda pestaña sobre la misma
    sesión (como al reabrir la app) se reengancha, recibe la pantalla y el
    fichero sigue con 1 línea.
  - El host queda limpio: sin daemon, socket, binario ni fichero de conteo.
- No se probó en el Pixel: el código es compartido, y la APK solo cambia por el
  binario del agente, que sale del mismo `agent/`. Se puede comprobar con la
  siguiente pre-release.

## Resultado

Hecha. Las sesiones de nivel 3 ejecutan su automatización de inicio solo en un
PTY nuevo, por tramas `INPUT`, y los reenganches no repiten nada. Superficies
actualizadas en el catálogo: [[AgentProtocol]], [[AgentTransport]] (pasa a
`Vigente`: el empaquetado y el cableado que tenía pendientes ya están hechos)
y [[ScriptRunner]].
