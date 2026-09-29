---
Nombre: 'AgentControl'
Tipo: 'Contrato'
Área: 'Terminal'
Feature: 'Resiliencia'
Estado: 'Vigente'
Ámbito: 'Feature'
Fuente: 'shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentControl.kt'
Entrada pública: 'io.github.danielperezmartinez.titanssh.terminal'
Resumen: 'Órdenes de control del agente de nivel 3 y su contrato. En el destino, el binario titan-agent tiene --status [--json], --close-session <id> y --stop (parada ordenada que cierra todas las sesiones), que hablan con el daemon por un canal de control sobre el mismo encuentro loopback + token con su propia marca de preámbulo (TTNACTL1/TTNACOK1) y una línea JSON de petición y otra de respuesta (agent/cmd/titan-agent/control.go). En la app, AgentControl(session, launch) ejecuta cada orden por exec sobre una SshSession abierta, sin tocar el protocolo por tramas, así que puede ir junto a un AgentTransport vivo; status() devuelve un AgentStatusReport (AgentStatus.kt): state running, stopped, legacy (daemon anterior al canal de control) o unreachable (tiene el candado y no responde), versiones del daemon y de la CLI, PID, sistema, memoria del árbol de procesos del daemon y, por sesión (AgentSessionReport), id titan-<id>, creación, último uso, desde cuándo no tiene cliente, clientes, historial en bytes y memoria de su shell. Horas en milisegundos Unix del reloj del destino, junto a nowMs. Un fallo lanza AgentControlException con la última línea de stderr.'
Última modificación: 2026-09-29T23:00:00+02:00
---

# AgentControl

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentControl.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentControl.kt),
[AgentStatus.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentStatus.kt)
y, en el agente,
[control.go](../../agent/cmd/titan-agent/control.go). El formato JSON está
fijado por dos tests espejo: `TestStatusJSONContract` (Go) y
`AgentWatchTest.parsesTheAgentContract` (Kotlin); **mantener ambos en
sincronía**. `AgentControlIntegrationTest` lo recorre contra un host real
(opt-in).

Nace de [[Transparencia y control del agente en el destino]]: las sesiones del
agente no caducan ([[ADR-0014 Sesiones del agente sin caducidad]]), así que la
app necesita verlas y cerrarlas de forma explícita.

Piezas relacionadas:

- [[AgentWatch]] — guarda lo último que se supo de cada agente y aplica los
  cierres pendientes con estas órdenes.
- [[AgentTransport]] — el canal de sesión; `AgentTransport.sanitizeId` da el id
  de agente de una sesión guardada.
- [[AgentInstaller]] — da el `AgentLaunch` con el que se ejecutan las órdenes.
