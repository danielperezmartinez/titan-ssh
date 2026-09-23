---
Nombre: "AgentProtocol"
Tipo: "Contrato"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/terminal/AgentProtocol.kt"
Entrada pública: "im.gar.titanssh.terminal"
Resumen: "Códec puro del protocolo por tramas cliente↔agente del nivel 3 de resiliencia (ADR-0008): binario, con prefijo de longitud, sobre el stdio del canal exec de SSH. Fuente de verdad del formato de cable (el agente Go en agent/ lo replica byte a byte). encode(frame)→ByteArray y FrameDecoder.feed(chunk)→List<AgentFrame> tolerante a troceo del stream. Tramas: HELLO/HELLO_OK/DATA/INPUT/RESIZE/REPLAY_FROM/ACK/BYE, con offsets de byte (DATA es autodescriptivo) para replay-desde-offset. Sin dependencias de plataforma ni de SSH; testeable headless (AgentProtocolTest). Probado en ambos lados y de punta a punta contra host real (lo usa [[AgentTransport]] en el cliente y el binario Go en el destino)."
Última modificación: 2026-09-23T20:10:00+02:00
---

# AgentProtocol

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentProtocol.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/terminal/AgentProtocol.kt);
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
