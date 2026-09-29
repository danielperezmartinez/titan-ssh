---
Nombre: "AgentTransport"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentTransport.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Extremo cliente del nivel 3 de resiliencia (ADR-0008): ejecuta titan-agent por un SshExecChannel y habla AgentProtocol con él. Recibe un AgentLaunch (ruta y shell del destino: sh, cmd o PowerShell) y lanza con AgentLaunch.command, que pone las comillas de esa shell. run() envía HELLO(sessionId,lastOffset,cols,rows) y bombea las tramas DATA del agente a un onOutput (el emulador de la pestaña) hasta que el canal cierra; sendInput/resize mandan INPUT/RESIZE y cada DATA se confirma con ACK (un ACK que llega con el canal ya cerrado se descarta). Mantiene appliedOffset (bytes aplicados) para reconectar con replay exacto, con dedupe de replay solapado y aceptación de huecos por buffer capado. Al recibir HELLO_OK avisa por onAttached(fresh) de si el agente creó la sesión (flag created; con agentes anteriores al flag, headOffset == 0) o se reenganchó a una viva, y si es nueva (o el head del agente va por detrás) reinicia appliedOffset a 0 para no descartar la salida del PTY nuevo. SessionTab lo usa para lanzar los scripts de inicio solo en un PTY nuevo. Lee también el stderr del front: si run() termina sin HELLO_OK, deja en unavailable el motivo (el BYE con motivo, la línea TITAN_AGENT_ERROR o el código de salida, 126/127 = E_NOEXEC), y SessionTab degrada al nivel 2/1 en la misma conexión en vez de tratarlo como una caída; sin código de salida se trata como caída. Serializa las escrituras (sendMutex) para no entrelazar tramas. Acompaña a AgentInstaller/AgentDeployer (instalación, ver AgentInstaller) y se enchufa en SessionTab cuando ResilienceLevel.AGENT y hay deployer (cableado en AppShell); sin él degrada al nivel 2/1. Vocabulario de motivos en AgentDiagnostics. AgentTransport.sanitizeId(id) es público: da el id de agente (titan-<id>) de una sesión guardada, el que viaja en HELLO y lista --status (ver AgentControl)."
Última modificación: 2026-09-29T23:00:00+02:00
---

# AgentTransport

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentTransport.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentTransport.kt);
su cobertura está en `AgentTransportTest` (headless, canal fake) y
`AgentTransportIntegrationTest` (host real, opt-in).

Es el extremo **cliente** del nivel 3 de resiliencia
([[ADR-0008 Diseño del agente de resiliencia nivel 3]]). Habla el protocolo
[[AgentProtocol]] sobre el canal `exec` de [[SshConnector]] (`SshExecChannel`)
contra el binario `titan-agent` (`agent/`, Go) instalado por `AgentInstaller`
(vía el `AgentDeployer` inyectado). Lo conduce [[SessionManager]] → `SessionTab`:
cuando la sesión pide `ResilienceLevel.AGENT` y hay un deployer, el tab enruta
entrada/salida/resize por el transporte en vez del shell; si no, degrada al nivel
2/1 (ver [[Resiliencia nivel 2 auto-tmux o screen]]).

Al abrir cada conexión, `onAttached(fresh)` indica si el agente ha creado un PTY
nuevo o se ha reenganchado a uno vivo. `SessionTab` solo lanza los scripts de
inicio en el primer caso (ver [[ScriptRunner]] y
[[Scripts de inicio por sesión sobre el agente]]).

Piezas relacionadas:

- [[AgentProtocol]] — el formato de cable que serializa/decodifica.
- [[AgentDiagnostics]] — los motivos por los que el nivel 3 no está disponible.
- [[AgentInstaller]] — instala el agente y da el `AgentLaunch` con el que se
  lanza.
- [[SshConnector]] — el `SshExecChannel` que transporta las tramas.
- [[Resiliencia nivel 3 agente propio en el destino]] — la tarea y el resto de
  subtareas (instalación, agente del destino).
