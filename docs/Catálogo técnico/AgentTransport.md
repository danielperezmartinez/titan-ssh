---
Nombre: "AgentTransport"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "En revisión"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentTransport.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Extremo cliente del nivel 3 de resiliencia (ADR-0008): ejecuta titan-agent por un SshExecChannel y habla AgentProtocol con él. run() envía HELLO(sessionId,lastOffset,cols,rows) y bombea las tramas DATA del agente a un onOutput (el emulador de la pestaña) hasta que el canal cierra; sendInput/resize mandan INPUT/RESIZE y cada DATA se confirma con ACK. Mantiene appliedOffset (bytes aplicados) para reconectar con replay exacto, con dedupe de replay solapado y aceptación de huecos por buffer capado. Serializa las escrituras (sendMutex) para no entrelazar tramas. Acompaña a AgentInstaller/AgentDeployer (instalación) y se enchufa en SessionTab cuando ResilienceLevel.AGENT y hay deployer; sin él degrada al nivel 2/1. EN REVISIÓN: falta empaquetar binarios y cablear el deployer en el arranque para uso desde la UI."
Última modificación: 2026-09-24T12:00:00+02:00
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

Piezas relacionadas:

- [[AgentProtocol]] — el formato de cable que serializa/decodifica.
- [[SshConnector]] — el `SshExecChannel` que transporta las tramas.
- [[Resiliencia nivel 3 agente propio en el destino]] — la tarea y el resto de
  subtareas (instalación, agente del destino).
