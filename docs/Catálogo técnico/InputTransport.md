---
Nombre: "InputTransport"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Mouse pad"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/InputTransport.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Extremo cliente de una sesión mouse pad (ADR-0016): ejecuta titan-agent --input por un SshExecChannel (con el AgentLaunch que da AgentInstaller) y le manda las tramas de entrada de AgentProtocol (PointerMove, PointerButton, Scroll, Text, Key). El agente las lleva a su ayudante de escritorio, que las inyecta, y contesta INPUT_READY al estar listo y cada vez que el escritorio se bloquea o se desbloquea; onReady(blocked) lo avisa en el hilo lector. No hay offsets ni replay: send() descarta lo que se envía sin canal, y al cerrarse el canal el ayudante suelta lo que quedara pulsado. Si run() termina sin INPUT_READY, deja en unavailable el motivo (BYE con motivo, línea TITAN_AGENT_ERROR o código de salida, como AgentTransport). Lo conduce SessionTab cuando la sesión es SessionType.MOUSEPAD; MousepadView pinta la pestaña y MousepadMotion convierte los gestos en deltas."
Última modificación: 2026-10-01T21:00:00+02:00
---

# InputTransport

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[InputTransport.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/InputTransport.kt);
su cobertura está en `InputTransportTest` (headless, canal fake).

Es el extremo **cliente** del mouse pad
([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]). Comparte
el encuadre de [[AgentProtocol]] y la instalación de [[AgentInstaller]] con el
nivel 3, pero no pasa por el daemon: el front `--input` del agente se conecta
directamente al ayudante de escritorio del usuario.

Piezas relacionadas:

- [[AgentTransport]] — el transporte del nivel 3, del que copia la forma de
  leer los rechazos del agente.
- [[AgentDiagnostics]] — los motivos (`E_NO_DESKTOP`, `E_INPUT_UNSUPPORTED`…)
  y `describeMousepad`.
- [[MousepadView]] — la pestaña que lo usa a través de `SessionTab.sendInput`.
- [[Mouse pad en destinos Windows]] — la tarea.
