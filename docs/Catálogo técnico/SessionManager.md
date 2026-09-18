---
Nombre: "SessionManager"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/terminal/SessionManager.kt"
Entrada pública: "im.gar.titanssh.terminal"
Resumen: "Dueño de las pestañas abiertas y del flujo de lanzamiento (config resuelta → motor → pestaña viva) que la lanzadera de Sesiones dejó pendiente. open(ResolvedConnection) crea un SessionTab, lo activa y conecta; activate/close/move(Left/Right) delegan el orden en el TabList puro. Expone tabs y activeId como StateFlow para la UI. Cada SessionTab gobierna una conexión + shell, alimenta un TerminalEmulator, expone status (TabPhase) y snapshot observables, resize real del PTY, TOFU inline (PendingHostKey) y el enganche onShellReady para los scripts de inicio."
Última modificación: 2026-09-18T15:45:00+02:00
---

# SessionManager

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[SessionManager.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/SessionManager.kt)
y la pestaña
[SessionTab.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/SessionTab.kt)
(`TabPhase`, `TabStatus`, `PendingHostKey`).

Contrato y colaboradores:

- **Lanzamiento**: `open(resolved)` es el cable config → motor que faltaba desde
  [[Panel de gestión de hosts y sesiones]]; resuelve credenciales con
  [[CredentialResolver]] en tiempo de conexión y conecta vía [[SshConnector]].
- **Orden de pestañas**: delegado en el modelo puro [[TabList]].
- **Estado por pestaña**: `TabPhase` (CONNECTING/CONNECTED/RECONNECTING/
  DISCONNECTED/FAILED); el estado RECONECTANDO existe pero aún no tiene driver
  (lo aporta [[Resiliencia de sesión ante microcortes de red]], ADR-0003 nivel 1).
- **TOFU**: `pendingHostKey` aflora la confirmación de primera vez
  ([[KnownHostsVerifier]], [[ADR-0005 Autenticación SSH y verificación de host]]).
- **Scripts de inicio**: `onShellReady(shell, resolved)` es el punto de enganche
  (hoy no-op) que desbloquea [[Scripts de inicio por sesión]].

Entregado en [[Terminal multipestaña con sesiones simultáneas]].
