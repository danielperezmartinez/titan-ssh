---
Nombre: 'Sistema de plugins'
Estado: 'Planificando'
Resumen: 'Sección de plugins en la app donde el usuario ve los plugins disponibles y los activa o desactiva. Según ADR-0032, un plugin es una pieza incluida en la app, nunca código descargado; activarlo puede desplegar en el destino lo que necesite y desactivarlo lo retira. Es la base de los plugins de CLIs de IA y de MCP.'
Decisiones: 'Los plugins son piezas incluidas en la app que se activan, nunca código descargado (ADR-0032).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T22:20:00+01:00
Última modificación: 2026-10-27T22:20:00+01:00
---

# Sistema de plugins

## Objetivo

Dar a la app una forma escalable de añadir funciones opcionales que el
usuario elige, empezando por [[Plugin de CLIs de agentes de IA]] y
[[Plugin de MCP para titan-ssh]]. La decisión de fondo ya está tomada en
[[ADR-0032 Plugins como piezas incluidas que se activan]]: los plugins
vienen dentro de la app y se activan; no se descargan.

## Análisis

Se decide al abordar la tarea. Puntos abiertos:

- Contrato de un plugin en `commonMain`: identidad, descripción, en qué
  plataformas existe, ajustes propios y a qué se engancha (eventos de la
  terminal, del agente, de la sesión).
- Dónde se activa: global, por host o por sesión guardada.
- Despliegue en el destino a través del agente (scripts, hooks,
  configuración) y su retirada al desactivar, sin dejar restos.
- Cómo se ve en la UI la sección de plugins y su estado por destino.
- Qué parte necesita el agente (p. ej. un comando para que un hook del
  destino le pase eventos a la app).

## Criterios de finalización

- La app tiene una sección de plugins donde se activan y desactivan.
- Hay un contrato documentado que los dos primeros plugins pueden usar sin
  tocar el núcleo más allá de sus puntos de enganche.
- Desactivar un plugin retira lo que desplegó en el destino.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
