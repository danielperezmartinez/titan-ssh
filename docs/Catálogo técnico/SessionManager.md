---
Nombre: "SessionManager"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionManager.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Dueño de las pestañas abiertas y del flujo de lanzamiento (config resuelta → motor → pestaña viva) que la lanzadera de Sesiones dejó pendiente. open(ResolvedConnection) crea un SessionTab, lo activa y conecta; activate/close/move(Left/Right) delegan el orden en el TabList puro. Expone tabs y activeId como StateFlow para la UI. Cada SessionTab gobierna una conexión + shell, alimenta un TerminalEmulator, expone status (TabPhase) y snapshot observables, resize real del PTY, TOFU inline (PendingHostKey), difunde un tee decodificado del output y ejecuta la automatización de arranque (ShellAutomation) en paralelo al pintado. Resiliencia nivel 1: ante un microcorte el SessionTab conserva el emulador (pantalla + scrollback), pasa a RECONNECTING y reconecta con backoff según ReconnectPolicy, reabriendo un shell nuevo en el MISMO emulador y reejecutando la automatización (onReconnected); una salida limpia (transporte vivo) cierra la pestaña en vez de reconectar."
Última modificación: 2026-09-24T12:00:00+02:00
---

# SessionManager

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[SessionManager.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionManager.kt)
y la pestaña
[SessionTab.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionTab.kt)
(`TabPhase`, `TabStatus`, `PendingHostKey`).

Contrato y colaboradores:

- **Lanzamiento**: `open(resolved)` es el cable config → motor que faltaba desde
  [[Panel de gestión de hosts y sesiones]]; resuelve credenciales con
  [[CredentialResolver]] en tiempo de conexión y conecta vía [[SshConnector]].
- **Orden de pestañas**: delegado en el modelo puro [[TabList]].
- **Estado por pestaña**: `TabPhase` (CONNECTING/CONNECTED/RECONNECTING/
  DISCONNECTED/FAILED). El `SessionTab` es su propio driver de reconexión
  (resiliencia nivel 1, ADR-0003, [[Resiliencia de sesión ante microcortes de red]]).
- **Resiliencia nivel 1**: tras haber estado vivo, un corte del transporte no
  cierra la pestaña — `runSession()` distingue caída (transporte abajo, dentro de
  `dropGraceMillis`) de salida limpia (transporte arriba → DISCONNECTED
  "Sesión finalizada"), y ante una caída reconecta con backoff exponencial
  (`ReconnectPolicy`: `maxAttempts`, backoff, `dropGraceMillis`) reabriendo un
  shell nuevo en el **mismo** `TerminalEmulator`, así que el scrollback sobrevive.
  El primer intento de conexión NO se auto-reintenta (fallo del host = FAILED);
  agotados los reintentos → DISCONNECTED con el motivo. El constructor de
  `SessionManager`/`SessionTab` acepta un `ReconnectPolicy` (por defecto `Default`).
- **TOFU**: `pendingHostKey` aflora la confirmación de primera vez
  ([[KnownHostsVerifier]], [[ADR-0005 Autenticación SSH y verificación de host]]).
- **Scripts de inicio y reconexión**: el constructor recibe una `ShellAutomation`
  (por defecto `None`); el tab la invoca con un `ShellIo` (send + tee de salida)
  una vez la shell está viva y en paralelo al pintado — `onShellReady` al conectar
  y `onReconnected` tras reconectar (respeta `ReconnectBehavior`). La
  implementación es [[ScriptRunner]] ([[Scripts de inicio por sesión]]). En cada
  (re)conexión el tee se recrea (replay vacío) para que la automatización de
  reconexión espere la salida del shell NUEVO ([[ptty-drops-early-input]]).

Entregado en [[Terminal multipestaña con sesiones simultáneas]]; resiliencia
nivel 1 en [[Resiliencia de sesión ante microcortes de red]].
