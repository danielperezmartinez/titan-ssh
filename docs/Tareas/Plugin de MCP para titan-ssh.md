---
Nombre: 'Plugin de MCP para titan-ssh'
Estado: 'Planificando'
Resumen: 'Plugin que ofrece un servidor MCP para que una IA use titan-ssh. Dos variantes abiertas: (a) que la IA use las conexiones de titan-ssh (hosts, sesiones persistentes, escritorio remoto) sin ver nunca las credenciales; (b) que la IA maneje la propia app, como herramienta de pruebas. Se decide al abordarla.'
Decisiones: ''
Bloqueada:
  - "[[Sistema de plugins]]"
Versión: ''
Fecha de creación: 2026-10-27T22:20:00+01:00
Última modificación: 2026-10-27T22:20:00+01:00
---

# Plugin de MCP para titan-ssh

## Objetivo

Que una IA (p. ej. Claude Code) pueda usar titan-ssh por MCP. Surgió al
ver que un agente de IA no puede manejar la app de escritorio para
probarla.

## Análisis

Dos variantes, las dos abiertas; se decide al abordar la tarea si se hace
una, otra o las dos (y si (b) merece tarea propia):

- **(a) Usar las conexiones de titan-ssh**: listar hosts y sesiones
  guardadas, abrir y leer sesiones persistentes del agente, mandar
  teclas, ver el escritorio remoto o capturarlo, y mandar puntero y
  teclado. Aporta frente a un `ssh` directo las sesiones que sobreviven a
  microcortes, el escritorio remoto y usar las claves guardadas sin
  exponerlas nunca a la IA.
- **(b) Manejar la propia app**: ver y pulsar su UI, como herramienta de
  pruebas y desarrollo más que como función para usuarios.

Seguridad (prioridad alta, ver
[[ADR-0001 Credenciales en almacén nativo del SO]]): solo en el propio
equipo, lista de hosts permitidos, confirmación para lo delicado, nunca
credenciales en las respuestas. Previsiblemente solo escritorio.

## Criterios de finalización

- Decidida la variante y su alcance.
- Una IA conectada por MCP hace lo previsto contra un destino de pruebas.
- Sin credenciales expuestas y con los límites de seguridad acordados,
  revisados según la regla 5.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
