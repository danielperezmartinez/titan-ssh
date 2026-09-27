---
Nombre: "SessionManager"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionManager.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Dueño de las pestañas abiertas y del flujo de lanzamiento (config resuelta → motor → pestaña viva) que la lanzadera de Sesiones dejó pendiente. open(ResolvedConnection) crea un SessionTab, lo activa y conecta; activate/close/move(Left/Right) delegan el orden en el TabList puro. Expone tabs y activeId como StateFlow para la UI. Cada SessionTab gobierna una conexión + shell, alimenta un TerminalEmulator, expone status (TabPhase) y snapshot observables, resize real del PTY, TOFU inline (PendingHostKey), difunde un tee decodificado del output y ejecuta la automatización de arranque (ShellAutomation) en paralelo al pintado. Resiliencia nivel 1: ante un microcorte el SessionTab conserva el emulador (pantalla + scrollback), pasa a RECONNECTING y reconecta con backoff según ReconnectPolicy (plazo de 15 min desde el corte, backoff hasta 10 s), reabriendo un shell nuevo en el MISMO emulador y reejecutando la automatización (onReconnected); una salida limpia (transporte vivo) cierra la pestaña en vez de reconectar. reconnectNow() corta la espera o vuelve a conectar una pestaña caída o fallida sin perder el historial; SessionManager recibe networkRestored (aviso de red de Android) y llama a onNetworkRestored() en cada pestaña. Si la pregunta TOFU sigue abierta cuando el saludo SSH caduca, la pestaña espera la respuesta y, si se acepta, vuelve a conectar sola. Expone también resilience (ResilienceStatus): el nivel efectivo (EffectiveLevel: agente, tmux/screen o base) y el motivo si el nivel 3 no está disponible o el aviso de systemd, con enableLinger() para resolverlo (ver AgentDiagnostics). Expone tunnels (TunnelStatus por túnel activado: WAITING, ACTIVE o FAILED con motivo): los abre en cada conexión, en los tres niveles, los cierra al caer o al cerrar la pestaña, y reintenta los que fallan por puerto ocupado (aquí o en el servidor) mientras la conexión siga viva (SessionTunnels)."
Última modificación: 2026-09-28T12:00:00+02:00
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
  (`ReconnectPolicy`: plazo `giveUpAfterMillis` de 15 min, `maxAttempts`
  opcional, backoff hasta 10 s, `dropGraceMillis`) reabriendo un
  shell nuevo en el **mismo** `TerminalEmulator`, así que el scrollback sobrevive.
  El primer intento de conexión NO se auto-reintenta (fallo del host = FAILED);
  agotado el plazo → DISCONNECTED con el motivo. El constructor de
  `SessionManager`/`SessionTab` acepta un `ReconnectPolicy` (por defecto `Default`).
- **Reconectar ya** ([[Reconexión que no se rinde tras un corte largo]]):
  `SessionTab.reconnectNow()` corta la espera del backoff o vuelve a lanzar la
  conexión en una pestaña `DISCONNECTED`/`FAILED`, en el mismo emulador.
  `SessionManager(networkRestored = …)` recibe el aviso de red de la plataforma
  (`networkRestored()`: `ConnectivityManager` en Android, vacío en escritorio) y
  llama a `onNetworkRestored()`, que reintenta las pestañas que esperan o que se
  rindieron, no las cerradas por salida limpia ni las que nunca conectaron.
- **TOFU**: `pendingHostKey` aflora la confirmación de primera vez
  ([[KnownHostsVerifier]], [[ADR-0005 Autenticación SSH y verificación de host]]).
  La respuesta vive en la pestaña, no en el verificador: si el saludo SSH caduca
  con la pregunta abierta, la pregunta sigue y, al aceptarla, la pestaña guarda
  la clave (`KnownHostsStore.trust`) y vuelve a conectar
  ([[Conectar tras confirmar tarde la clave del servidor]]).
- **Scripts de inicio y reconexión**: el constructor recibe una `ShellAutomation`
  (por defecto `None`); el tab la invoca con un `ShellIo` (send + tee de salida)
  una vez la shell está viva y en paralelo al pintado — `onShellReady` al conectar
  y `onReconnected` tras reconectar (respeta `ReconnectBehavior`). La
  implementación es [[ScriptRunner]] ([[Scripts de inicio por sesión]]). En cada
  (re)conexión el tee se recrea (replay vacío) para que la automatización de
  reconexión espere la salida del shell NUEVO ([[ptty-drops-early-input]]).

Entregado en [[Terminal multipestaña con sesiones simultáneas]]; resiliencia
nivel 1 en [[Resiliencia de sesión ante microcortes de red]].
