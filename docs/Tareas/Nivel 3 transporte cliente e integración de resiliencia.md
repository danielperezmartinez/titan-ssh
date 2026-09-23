---
Nombre: Nivel 3 transporte cliente e integración de resiliencia
Estado: Hecha
Resumen: 'ENTREGADO y verificado de punta a punta contra host real. AgentTransport (commonMain) habla AgentProtocol sobre el SshExecChannel: ejecuta titan-agent, envía HELLO(lastOffset,cols,rows), aplica DATA al TerminalEmulator con dedupe por offset (replay solapado se descarta, un hueco por buffer capado se acepta), envía INPUT/RESIZE y ACK; mantiene appliedOffset entre reconexiones. Integrado en SessionTab: si ResilienceLevel.AGENT y hay un AgentDeployer (inyectado, jvmShared vía agentDeployer()), enruta por el agente; sin deployer o si la instalación falla, degrada limpiamente al nivel 2/1 (StartScriptAutomation). SessionManager propaga el deployer (null por defecto → cero cambio para BASE/AUTO_MULTIPLEXER). Verificado headless (AgentTransportTest con canal fake) y real (AgentTransportIntegrationTest: instala, teclea echo, ve la salida, y al reconectar reproduce el historial). Falta solo para uso desde la UI: empaquetar los binarios ([[titan-agent distribución multi-arch e instalación]]) y cablear un deployer al arrancar la app.'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]]. Consume [[AgentProtocol]] y el canal de [[Nivel 3 canal exec en el motor SSH]]; inversión de dependencia con AgentDeployer (commonMain) implementado en jvmShared sobre AgentInstaller. Se integra con [[SessionManager]] y el flujo de [[Resiliencia de sesión ante microcortes de red]] / [[Resiliencia nivel 2 auto-tmux o screen]]. Superficie catalogada en [[AgentTransport]] y [[AgentProtocol]].'
Bloqueada: []
Fecha de creación: 2026-09-19T18:25:00+02:00
Última modificación: 2026-09-19T19:55:00+02:00
---

# Nivel 3: transporte cliente e integración de resiliencia

## Objetivo

Cerrar el nivel 3 end-to-end: el cliente habla con `titan-agent` por
`AgentProtocol` y la persistencia total queda enganchada al flujo de resiliencia
existente, con degradación limpia.

## Criterios de finalización

- **`AgentTransport`** (`commonMain`): ejecuta el agente por el canal exec, envía
  `HELLO(sessionId,lastOffset,cols,rows)`, aplica `DATA` al `TerminalEmulator` de
  la pestaña (mismo emulador entre reconexiones), envía `INPUT`/`RESIZE` y `ACK`.
  Mantiene `appliedOffset` con **dedupe**: replay solapado se descarta, un hueco
  (buffer capado) se acepta tal cual. ✅
- **Reconexión**: nuevo `exec` + `HELLO(appliedOffset)`; el agente reengancha y
  reproduce lo que faltaba (recuperación exacta, no shell nuevo). ✅
- **Integración**: en `SessionTab`, si `resilienceLevel == AGENT` y hay
  `AgentDeployer`, enruta por el agente; `sendBytes`/`resize`/`close` van al
  transporte. Si no hay deployer o la instalación falla → **degrada** al nivel
  2/1 (cae al camino shell + `StartScriptAutomation`). ✅
- Cobertura headless del transporte con un agente fake que habla `AgentProtocol`.
  ✅ (`AgentTransportTest`.)

## Diseño

`AgentDeployer` (commonMain, `fun interface`) invierte la dependencia: `SessionTab`
(commonMain) no puede ver `AgentInstaller` (jvmShared), así que recibe un
`AgentDeployer` que `SessionManager` propaga (null por defecto). La fábrica
`agentDeployer(version, binaryFor)` (jvmShared) lo implementa sobre
`AgentInstaller`. Con deployer null, un `AGENT` se comporta **exactamente** como
hoy (nivel 2/1) — cero regresión.

## Verificación

- **Headless** (`AgentTransportTest`, 2 casos): HELLO con id saneado + tamaño;
  `DATA` aplicado con dedupe de replay solapado y aceptación de hueco; `INPUT` se
  serializa como trama; cada `DATA` produce `ACK`; `RESIZE` emite su trama. Verde.
- **Host real** ([[ssh-test-host]], `AgentTransportIntegrationTest`, opt-in,
  `tests=1 failures=0`): instala el agente, `AgentTransport` lo conduce sobre una
  sesión SSH viva, `echo <marker>` vuelve por la salida, y una **segunda**
  transporte con replay desde 0 reengancha al daemon superviviente y recupera el
  historial (incluye el marker) sin teclear. Host limpiado tras la prueba.

## Notas

- **Falta solo para uso desde la UI** (no del transporte): empaquetar binarios
  ([[titan-agent distribución multi-arch e instalación]]) y cablear un
  `AgentDeployer` al construir el `SessionManager` en el arranque de la app.
- **Fuera de alcance (follow-up):** ejecutar los scripts de inicio por sesión
  sobre el agente (hoy la ruta del agente no invoca `StartScriptAutomation`; el
  `$SHELL -il` del agente sí corre los rc del usuario). La salida en pantalla
  completa depende del emulador, ya cubierto.
