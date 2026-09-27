---
Nombre: 'Ejecutar los túneles de las sesiones'
Estado: 'Pendiente'
Resumen: 'Los túneles de una sesión (reenvío local, remoto y proxy SOCKS dinámico) se configuran y se guardan desde el panel de hosts y sesiones, pero el motor SSH nunca los abre: no hay código de reenvío de puertos. Objetivo: abrirlos al conectar la sesión sobre la misma conexión SSH, reabrirlos tras un microcorte, mostrar en la pestaña cuáles están activos y cuáles han fallado (p. ej. puerto ocupado) y cerrarlos al cerrar la pestaña. Después se valorará una biblioteca de túneles como plantillas.'
Decisiones: 'Sale del paso 10 de [[Seguimiento de tareas pendientes]] por decisión del usuario del 2026-09-27 ([[ADR-0013 Biblioteca de scripts unificada con los snippets]], punto 7): primero los túneles tienen que funcionar. Modelo en [[ADR-0007 Modelo y persistencia de configuración]].'
Bloqueada: []
Fecha de creación: 2026-09-27T16:45:00+02:00
Última modificación: 2026-09-27T16:45:00+02:00
---

# Ejecutar los túneles de las sesiones

## Objetivo

Que los túneles que el usuario configura en una sesión funcionen de verdad
mientras la pestaña está abierta.

## Contexto

- El modelo (`Tunnel`, `TunnelType`: `LOCAL`, `REMOTE`, `DYNAMIC_SOCKS`) y su
  editor existen desde [[Panel de gestión de hosts y sesiones]] y
  [[Editores de script y túnel como pantalla propia]].
- Revisado el 2026-09-27: ni `SessionTab` ni el conector de sshj abren ningún
  reenvío. Los túneles se guardan y nada más.
- sshj tiene reenvío local (`LocalPortForwarder`) y remoto
  (`RemotePortForwarder`). El SOCKS dinámico no viene hecho: hay que
  implementarlo sobre canales `direct-tcpip`.

## Criterios de finalización

- Los túneles activados se abren al conectar, sobre la conexión SSH de la
  pestaña, en los tres niveles de resiliencia (en el nivel 3 el agente no
  interviene: el túnel va por la conexión SSH, no por el PTY).
- Tras un microcorte se reabren con la conexión nueva.
- La pestaña muestra qué túneles están activos y cuáles han fallado, con el
  motivo (puerto local ocupado, el servidor rechaza el reenvío…), sin cortar la
  sesión.
- Se cierran al cerrar la pestaña.
- En Android: decidir qué pasa con el túnel cuando la app va a segundo plano.
- Tests del reenvío contra un sshd en Docker.

## Después

Valorar una biblioteca de túneles como plantillas (se pidió en
[[Scripts y túneles reutilizables de primera clase]]): los puertos y destinos
suelen ser propios de cada sesión.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
