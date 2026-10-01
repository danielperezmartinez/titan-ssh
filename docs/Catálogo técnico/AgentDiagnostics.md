---
Nombre: 'AgentDiagnostics'
Tipo: 'Contrato'
Área: 'Terminal'
Feature: 'Resiliencia'
Estado: 'Vigente'
Ámbito: 'Feature'
Fuente: 'shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentDiagnostics.kt'
Entrada pública: 'io.github.danielperezmartinez.titanssh.terminal'
Resumen: 'Vocabulario del diagnóstico del nivel 3 (ADR-0009 §5 y §7): por qué una pestaña no usa el agente o qué lo pone en riesgo. AgentIssue(code, detail) es el motivo; AgentDeployment (Ready con aviso opcional / Unavailable) es lo que devuelve un AgentDeployer. AgentDiagnostics reúne los códigos del agente (E_STATE_DIR, E_LOCK, E_DAEMON_START, E_AUTH, E_JOB_NO_BREAKAWAY, E_NO_CONPTY, E_PTY) y los del cliente (E_UNSUPPORTED_TARGET, E_NO_BINARY, E_UPLOAD, E_CHECKSUM, E_NOEXEC, E_AGENT_EXIT y el aviso E_SYSTEMD_KILL); lee la línea TITAN_AGENT_ERROR del stderr del front y el motivo de un BYE (parseContract, parseReason), clasifica un front que termina sin HELLO_OK por su código de salida (classifyFrontExit: sin código = caída, 126/127 = E_NOEXEC), hace la sonda de systemd (SYSTEMD_PROBE: busctl o logind.conf, más loginctl show-user -p Linger) y la interpreta (parseSystemdProbe), y redacta el texto en español con lo que hay que hacer (describe). Puro y testeable sin red. Para el mouse pad (ADR-0016): E_INPUT_UNSUPPORTED, E_NO_DESKTOP y E_DESKTOP_TASK, y describeMousepad, que explica el motivo con el prefijo del mouse pad en vez del del nivel 3.'
Última modificación: 2026-10-01T21:00:00+02:00
---

# AgentDiagnostics

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentDiagnostics.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentDiagnostics.kt).
Cobertura: `AgentDiagnosticsTest` (parseo, clasificación, textos y la decisión
de [[AgentTransport]] entre "no disponible" y caída), `SessionTabAgentDegradeTest`
(la pestaña degrada en la misma conexión y no reintenta el agente) y
`AgentDiagnosticsIntegrationTest` (host real, opt-in; cambia los permisos del
directorio de estado, así que va contra un sshd desechable).

Quién produce cada motivo:

- [[AgentInstaller]]: `E_UNSUPPORTED_TARGET`, `E_NO_BINARY`, `E_UPLOAD`,
  `E_CHECKSUM` y el aviso `E_SYSTEMD_KILL` (solo en destinos Linux).
- [[AgentTransport]]: los códigos del agente (por la línea `TITAN_AGENT_ERROR`
  del front o por el `BYE` con motivo de [[AgentProtocol]]), `E_NOEXEC` y
  `E_AGENT_EXIT`.

`SessionTab` guarda el motivo durante la vida de la pestaña, lo publica en
`resilience` junto al nivel efectivo y ofrece `enableLinger()` para el aviso de
systemd. [[TerminalView]] lo muestra bajo la franja de estado.

Para añadir un código: definirlo en el agente (`agent/cmd/titan-agent/errors.go`
o `internal/session/pty.go`) o en el cliente, añadir aquí su constante y su
texto en `describe`, y listarlo en el contrato de errores de
[[Nivel 3 portable a todos los destinos]].

Ver [[Diagnóstico cuando el nivel 3 no está disponible]].
