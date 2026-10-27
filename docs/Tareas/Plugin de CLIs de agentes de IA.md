---
Nombre: 'Plugin de CLIs de agentes de IA'
Estado: 'Planificando'
Resumen: 'Plugin que integra CLIs de agentes de IA (Claude Code, Codex, agy) con las pestañas: nombre de la pestaña desde la sesión del agente, notificación cuando termina un trabajo o necesita atención, y otros eventos por descubrir. Se apoya en hooks de cada CLI en el destino que pasan eventos estructurados a la app a través de titan-agent.'
Decisiones: ''
Bloqueada:
  - "[[Sistema de plugins]]"
Versión: ''
Fecha de creación: 2026-10-27T22:20:00+01:00
Última modificación: 2026-10-27T22:20:00+01:00
---

# Plugin de CLIs de agentes de IA

## Objetivo

Que la app entienda lo que hacen las CLIs de agentes de IA que corren en
una sesión y lo refleje: cambiar el nombre de la pestaña cuando la CLI
cambia el de su sesión, notificar cuando termina un trabajo o necesita al
usuario (permisos, preguntas), y lo que se descubra que aportan.

## Análisis

Primera evaluación (2026-10-27), a comprobar al abordar la tarea:

- **Por la salida de la terminal**: Claude Code pone el título de la
  terminal (OSC 0/2) y avisa con BEL o secuencias de notificación
  (OSC 9 y similares); Codex también puede notificar así. Esto es genérico
  y va en el núcleo de la terminal, no en el plugin: ver
  [[Título de la pestaña y avisos desde la terminal]].
- **Por los hooks de cada CLI** (lo propio del plugin): Claude Code tiene
  hooks de eventos (notificación de atención o permiso, fin de turno,
  inicio de sesión…) y Codex un ajuste `notify` que ejecuta un programa al
  terminar cada turno. El plugin desplegaría en el destino hooks que
  llaman a `titan-agent`, y el agente pasaría a la app eventos
  estructurados, sin adivinar a partir de la salida.
- **agy**: sin datos fiables todavía; investigar qué eventos ofrece.
- El agente ya guarda por sesión el último título OSC 0/2
  (`AgentStatus.title`, ver [[Detalle de las sesiones del agente en el panel]]).
- **Límite**: en el móvil la notificación solo llega mientras la app tenga
  una conexión con el destino; por coste cero no hay servidor de push.

## Criterios de finalización

- Con el plugin activo, la pestaña toma el nombre de la sesión de la CLI.
- Llega una notificación cuando la CLI termina un trabajo o pide atención,
  en escritorio y en Android.
- Probado con Claude Code y Codex en un destino real; agy, según lo que
  ofrezca.
- Desactivar el plugin retira los hooks del destino sin tocar la
  configuración propia del usuario.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
