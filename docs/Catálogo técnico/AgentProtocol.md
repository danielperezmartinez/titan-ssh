---
Nombre: "AgentProtocol"
Tipo: "Contrato"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Códec puro del protocolo por tramas cliente↔agente del nivel 3 de resiliencia (ADR-0008): binario, con prefijo de longitud, sobre el stdio del canal exec de SSH. Fuente de verdad del formato de cable (el agente Go en agent/ lo replica byte a byte). encode(frame)→ByteArray y FrameDecoder.feed(chunk)→List<AgentFrame> tolerante a troceo del stream. Tramas: HELLO/HELLO_OK/DATA/INPUT/RESIZE/REPLAY_FROM/ACK/BYE, con offsets de byte (DATA es autodescriptivo) para replay-desde-offset. HELLO_OK lleva un byte final de flags opcional (bit 0 = created: el agente creó la sesión); los agentes anteriores no lo envían y se decodifica como created = null. BYE admite un motivo opcional (<código> <mensaje> del contrato TITAN_AGENT_ERROR) con el que el agente rechaza una sesión en lugar de HELLO_OK; vacío, se codifica como siempre. Sin dependencias de plataforma ni de SSH; testeable headless (AgentProtocolTest). Probado en ambos lados y de punta a punta contra host real (lo usa [[AgentTransport]] en el cliente y el binario Go en el destino)."
Última modificación: 2026-09-27T14:00:00+02:00
---

# AgentProtocol

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentProtocol.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt);
su cobertura está en `AgentProtocolTest`.

Define el formato de cable del **nivel 3** de resiliencia
([[ADR-0008 Diseño del agente de resiliencia nivel 3]]): el cliente conecta por
SSH (sshj) y hace `exec` de `titan-agent` en el destino, y ambos hablan este
protocolo por tramas sobre el stdio del canal exec. El binario del agente
(`agent/`, Go) **replica** este mismo formato en `internal/protocol`; los dos
deben mantenerse en sincronía.

`AgentProtocol` solo (de)serializa tramas y tolera el troceo arbitrario del
stream con un `FrameDecoder` acumulador. La **semántica** de offsets, replay y
ACK vive en la capa de transporte: [[AgentTransport]] en el cliente y el binario
`titan-agent` (`agent/`, Go) en el destino, que replica este formato en
`internal/protocol`. Los tres deben mantenerse en sincronía.

**Orden obligatorio:** `HELLO` debe ser la **primera** trama de cada conexión.
El daemon cierra la conexión si recibe otra trama antes, o si el `HELLO` no
llega en 10 s (`helloTimeout` en `cmd/titan-agent/daemon.go`). `AgentTransport`
ya lo envía primero. Ver
[[titan-agent fuga previa al HELLO y permisos del socket]].

**`HELLO_OK` y sesiones nuevas:** tras los dos offsets, `HELLO_OK` lleva un
byte de flags cuyo bit 0 (`created`) dice si ese attach ha creado la sesión.
El byte es opcional en los dos sentidos: los agentes anteriores envían solo 16
bytes (se decodifica como `created = null`) y los clientes anteriores ignoran
el byte de más. Si la sesión es nueva, el agente reproduce desde el offset 0
aunque el `HELLO` pida otro. Lo usa
[[Scripts de inicio por sesión sobre el agente]].

**`BYE` con motivo:** el agente puede enviar `BYE` en lugar de `HELLO_OK` para
rechazar la sesión. Su payload opcional es el motivo, `<código> <mensaje>` del
contrato `TITAN_AGENT_ERROR` (p. ej. `E_NO_CONPTY …`). Vacío se codifica igual
que antes, así que es compatible en los dos sentidos. Lo interpreta
[[AgentDiagnostics]] y lo introduce
[[Diagnóstico cuando el nivel 3 no está disponible]].
