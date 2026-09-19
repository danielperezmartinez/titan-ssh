---
Nombre: Resiliencia nivel 3 agente propio en el destino
Estado: Pendiente
Resumen: 'Nivel 3 del modelo de resiliencia (ADR-0003): componente ligero de titan-ssh instalable en la máquina remota que mantiene PTYs persistentes y hace buffer/replay al reconectar, para persistencia total ("app en ambos extremos"). Si habla SSH, construible con Apache MINA SSHD ([[ADR-0004 Librería SSH]]). Mejora opcional máxima sobre los niveles 1 y 2; añade fricción de instalación y una superficie de seguridad a diseñar con cuidado.'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]] y reserva Apache MINA SSHD para el agente en [[ADR-0004 Librería SSH]]. Se apoya en los niveles 1-2 de [[Resiliencia de sesión ante microcortes de red]].
Bloqueada: []
Fecha de creación: 2026-09-19T16:45:00+02:00
Última modificación: 2026-09-19T16:45:00+02:00
---

# Resiliencia nivel 3: agente propio en el destino

## Objetivo

Persistencia total de la sesión ("no perder nunca el trabajo") mediante un
componente propio de titan-ssh en la máquina remota que desacopla la sesión del
transporte: mantiene los PTYs vivos y, al reconectar, reproduce el buffer para que
el cliente recupere exactamente el estado, incluso tras una caída total.

## Criterios de finalización (borrador)

- Agente ligero instalable en el destino con PTYs persistentes.
- Buffer/replay del output al reconectar; recuperación del proceso en primer plano.
- Protocolo cliente↔agente (si habla SSH, sobre Apache MINA SSHD; ver
  [[ADR-0004 Librería SSH]]).
- Diseño de seguridad del agente (autenticación, superficie de ataque,
  instalación/actualización) tratado explícitamente.

## Notas

- Interacción con `ResilienceLevel.AGENT` del modelo de config.
- Es la pieza de mayor alcance y fricción del modelo; probablemente requiera su
  propia ADR de diseño e incluso subtareas.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
