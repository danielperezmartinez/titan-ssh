---
Nombre: Resiliencia de sesión ante microcortes de red
Estado: Hecha
Resumen: 'ENTREGADO nivel 1 (reconexión de cliente): un microcorte ya no cierra la pestaña — el SessionTab conserva el emulador (pantalla + scrollback), pasa a RECONNECTING y reconecta con backoff (ReconnectPolicy), reabriendo un shell nuevo en el MISMO emulador y reejecutando la automatización al reconectar (onReconnected según ReconnectBehavior: nada / solo cd / cadena completa). Distingue caída (transporte abajo) de salida limpia (transporte arriba → fin de sesión). Verificado headless (SessionTabReconnectTest 4/4 + ScriptRunnerTest 13/13) y build de ambos targets. Cumple los criterios de finalización de la tarea. Niveles 2 (auto-tmux/screen) y 3 (agente propio) quedan como tareas propias del roadmap (ADR-0003).'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]] y [[ADR-0004 Librería SSH]]. Hereda la ejecución al reconectar (fase ON_RECONNECT y `ReconnectBehavior`) de [[Scripts de inicio por sesión]], reutilizando [[ScriptRunner]]. Superficie catalogada en [[SessionManager]] y [[ScriptRunner]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-19T16:45:00+02:00
---

# Resiliencia de sesión ante microcortes de red

## Objetivo

Que los microcortes de red no cierren la terminal. La pestaña afectada debe
quedar en espera, almacenar su estado y reanudarse al recuperar la conexión, de
forma que para el usuario sea como si no hubiera pasado nada. Como mucho se
muestra un loader de "reconectando", pero **nunca se pierde el trabajo en curso**.
Este es uno de los diferenciadores principales frente a Termius.

## Criterios de finalización

- Un microcorte de red no cierra la pestaña ni la sesión.
- Se conserva el estado de la sesión y se reanuda automáticamente al reconectar.
- La única señal visible aceptable durante la interrupción es un loader de
  "reconectando".

## Alcance (modelo por niveles)

Los tres niveles están dentro del alcance del proyecto, según
[[ADR-0003 Modelo de resiliencia por niveles]]. **Esta tarea entrega el nivel 1**,
que es el que satisface los criterios de finalización; los niveles 2 y 3 son
mejoras opcionales de persistencia total y se llevan como tareas propias:

1. **Reconexión de cliente (base) — HECHO.** Reconexión SSH por el lado del
   cliente y restauración del estado/scrollback de la pestaña, sin requisitos en
   el servidor. Es el mínimo garantizado. Se apoya en el heartbeat de sshj para
   detectar caídas; el scrollback se mantiene en el cliente (mismo
   `TerminalEmulator` entre reconexiones). Limitación asumida: si la conexión cae
   del todo, un proceso en primer plano del shell remoto muere; al reconectar se
   abre un shell nuevo y se reejecuta la automatización `ON_RECONNECT` según
   `ReconnectBehavior` (re-ejecutar todo / solo restaurar `cd` / no hacer nada),
   reutilizando [[ScriptRunner]]. Este cableado, heredado de
   [[Scripts de inicio por sesión]], queda resuelto.
2. **Auto-tmux/screen cuando existan** → [[Resiliencia nivel 2 auto-tmux o screen]].
3. **Agente propio en el destino** → [[Resiliencia nivel 3 agente propio en el destino]].

La librería SSH ya está decidida (sshj cliente / MINA agente, [[ADR-0004 Librería SSH]]).

El nivel 1 se apoya en la sesión y el heartbeat que expone
[[Motor de conexión SSH]] (ya `Hecha`).

## Diseño del nivel 1 (entregado)

- **Detección y clasificación.** `SessionTab.runSession()` conecta y, tras cada
  sesión viva, clasifica por qué terminó el flujo de salida del shell: si el
  transporte sigue arriba dentro de `dropGraceMillis` fue una **salida limpia**
  (el usuario hizo `exit`) → `DISCONNECTED` "Sesión finalizada"; si el transporte
  cae fue una **caída** → reconexión. La ventana de gracia absorbe el retraso de
  la detección por heartbeat de sshj (`SshjSession` observa `ssh.isConnected`).
- **Bucle de reconexión.** Solo tras haber estado vivo al menos una vez: pasa a
  `RECONNECTING`, espera un backoff exponencial acotado (`ReconnectPolicy`:
  `maxAttempts`, `initialBackoffMillis`, `maxBackoffMillis`) y reconecta. Una
  sesión sana resetea el contador. El **primer** intento de conexión NO se
  auto-reintenta (un host inalcanzable es `FAILED`, no un microcorte). Fallos
  fatales (clave de host rechazada, autenticación) nunca se reintentan. Agotados
  los reintentos → `DISCONNECTED` con el motivo.
- **Preservación del trabajo.** El `TerminalEmulator` (pantalla + scrollback) es
  el mismo objeto entre reconexiones: el shell nuevo pinta a continuación del
  historial, sin perderlo. El único cambio visible es el estado `RECONNECTING`
  ("Reconectando… (intento N/M)"), que la `StatusStrip` de [[TerminalView]] ya
  mostraba.
- **Reejecución al reconectar.** `ShellAutomation` gana `onReconnected`;
  `StartScriptAutomation` la implementa según `Session.effectiveReconnectBehavior()`
  (el `ReconnectBehavior` del primer script `ON_RECONNECT` habilitado; sin
  ninguno, por defecto `RESTORE_CD_ONLY`, que es la victoria mínima que promete el
  producto: volver al directorio de trabajo). En cada (re)conexión el tee de
  salida se recrea con replay vacío para que la automatización espere la salida
  del shell **nuevo** y no un chunk previo a la caída ([[ptty-drops-early-input]]).

## Verificación

- **Compilación (ambos targets) OK** (`BUILD SUCCESSFUL`):
  `:shared:compileAndroidMain`, `:shared:compileKotlinDesktop`,
  `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin` (los dos warnings
  de `LocalClipboardManager` deprecado son preexistentes y ajenos).
- **Headless — reconexión** (`SessionTabReconnectTest`, `:shared:desktopTest`):
  `tests=4 skipped=0 failures=0`. Con fakes de `SshConnector`/`SshSession`/
  `SshShell` sobre tiempo virtual: reconecta tras una caída y **conserva el
  scrollback** (texto previo y posterior en un único buffer); una salida limpia
  NO reconecta (`DISCONNECTED` "Sesión finalizada", 1 sola conexión); agota
  `maxAttempts` y reporta el abandono (1 + maxAttempts conexiones); el primer
  fallo de conexión es `FAILED` y no se reintenta.
- **Headless — reejecución** (`ScriptRunnerTest` ampliado): `tests=13 skipped=0
  failures=0`. `onReconnected` con `RESTORE_CD_ONLY` envía solo el `cd`;
  `RERUN_ALL` la cadena `cd + ON_SHELL_START + POST_INIT + ON_RECONNECT`; `NONE`
  no envía nada.
- **Suite de escritorio completa**: 15 suites verdes, `failures=0` (los tests de
  integración opt-in `SshjIntegrationTest`/`SessionTabIntegrationTest` se saltan
  sin credenciales).
- **No automatizado:** una prueba de caída de red real contra un host
  (provocar un microcorte de forma determinista) queda fuera por ser difícil de
  hacer estable; la señal de caída del motor ya está verificada real por
  `SshjIntegrationTest` y el resto de la lógica es una máquina de estados de
  cliente cubierta headless de forma determinista.

## Resultado

Nivel 1 de resiliencia entregado (paquete `im.gar.titanssh.terminal`):

- **`SessionTab` reescrito con ciclo conectar-reconectar** (`runSession()` +
  `connectOnce()`): detección caída vs salida limpia mediante la ventana de
  gracia sobre `SshSession.state`, backoff acotado, preservación del emulador y
  `RECONNECTING` como única señal.
- **`ReconnectPolicy`** (nuevo): cadencia de reintentos (`maxAttempts`, backoff
  exponencial, `dropGraceMillis`); inyectable en `SessionManager`/`SessionTab`,
  por defecto `Default`.
- **`ShellAutomation.onReconnected`** (nuevo hook, no-op por defecto) +
  **`Session.effectiveReconnectBehavior()`**; `StartScriptAutomation` reejecuta la
  cadena según el `ReconnectBehavior` elegido. Cierra el pendiente que
  [[Scripts de inicio por sesión]] había diferido (fase `ON_RECONNECT`).
- Superficie actualizada en el catálogo: [[SessionManager]] y [[ScriptRunner]].
