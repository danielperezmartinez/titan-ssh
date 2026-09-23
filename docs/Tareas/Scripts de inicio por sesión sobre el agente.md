---
Nombre: Scripts de inicio por sesión sobre el agente
Estado: Pendiente
Resumen: 'Follow-up del nivel 3 (ADR-0008): ejecutar los scripts de inicio por sesión (cd inicial, ON_SHELL_START, POST_INIT) también cuando la sesión va por la ruta del agente (ResilienceLevel.AGENT). Hoy SessionTab, al enrutar por AgentTransport, NO invoca StartScriptAutomation, así que el $SHELL -il del agente corre los rc del usuario pero no la automatización por sesión de titan. Al RECONECTAR no deben reejecutarse (el agente reproduce el estado); solo en la primera creación de la sesión del agente. Requiere una vía para que la automatización mande input por INPUT frames (adaptar ShellIo sobre AgentTransport, o un hook equivalente).'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]] y reutiliza [[ScriptRunner]] / StartScriptAutomation de [[Scripts de inicio por sesión]]. Se apoya en [[AgentTransport]].'
Bloqueada: []
Fecha de creación: 2026-09-23T08:05:00+02:00
Última modificación: 2026-09-23T08:05:00+02:00
---

# Scripts de inicio por sesión sobre el agente

## Objetivo

Que una sesión con `ResilienceLevel.AGENT` corra sus scripts de inicio por sesión
igual que las de nivel 1/2, sin perder la ventaja de que al reconectar el agente
reproduce el estado (no se reejecutan).

## Contexto

En `SessionTab`, cuando la sesión enruta por `AgentTransport` (nivel 3), se
**devuelve antes** de la ruta shell + `StartScriptAutomation`. Efecto actual:

- El `$SHELL -il` que arranca el agente **sí** corre los rc del usuario
  (`.bashrc`/`.profile`).
- Pero la **automatización por sesión de titan** (el `cd` inicial, los scripts
  `ON_SHELL_START` y `POST_INIT`) **no** se ejecuta.

## Criterios de finalización (borrador)

- En la **primera** creación de la sesión del agente (no en reenganches), correr
  la cadena de arranque de la sesión.
- **No** reejecutar en reconexiones: el agente ya reproduce el estado; distinguir
  "sesión del agente recién creada" de "reenganche" (se puede inferir de
  `appliedOffset == 0` / `HELLO_OK` con `headOffset == 0`, o de una señal del
  agente).
- Vía de entrada para la automatización: mandar el input por `INPUT` frames
  (p. ej. un `ShellIo` respaldado por `AgentTransport`, o un hook equivalente que
  `ScriptRunner` pueda usar).
- Respetar el `ReconnectBehavior` sigue siendo cosa del nivel 1; aquí el foco es
  el arranque.

## Notas

- Cuidado con el `awaitReady` / centinelas de `ScriptRunner`: sobre el agente la
  salida llega por `DATA`, no por `SshShell.output`.
