---
Nombre: 'Elegir el monitor en el escritorio remoto'
Estado: 'Pendiente'
Estado desde: 2026-11-18
Resumen: 'Poder ver en el escritorio remoto un solo monitor del destino en vez de todos juntos, para que en una pantalla pequeña la imagen no se encoja a la mitad cuando el destino tiene dos monitores. Necesita que el agente diga qué monitores hay y dónde está cada uno (o que capture uno solo), y un selector «pantalla: todas | 1 | 2» en los controles del visor.'
Decisiones: 'Sale del diseño de [[Controles del visor del escritorio remoto con barra movible]] (2026-11-18), que ya reserva el selector. ADR-0031 dejó elegir monitor para una trama posterior.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-18T20:25:00+01:00
Última modificación: 2026-11-18T20:25:00+01:00
---

# Elegir el monitor en el escritorio remoto

## Objetivo

Con un destino de varios monitores, ver solo uno de ellos a buen tamaño.

## Contexto

- El agente compone todos los monitores en una sola imagen
  ([[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]],
  apartado 5).
- Opciones a valorar: que el agente mande la geometría de cada monitor y la
  app recorte y limite el puntero a él, o que el agente capture solo el
  monitor elegido (menos ancho de banda).

## Criterios de finalización

- Selector `pantalla: todas | 1 | 2…` en la franja (escritorio) y en el panel
  `[#] vista` (móvil), que solo aparece con más de un monitor.
- Con un monitor elegido, la imagen y el puntero se limitan a él.
- Compatible con agentes y apps anteriores.

## Verificación

<Se rellena al completar.>

## Cómo probarlo

<Al pasar a `Por probar`.>

## Resultado

<Se rellena al completar.>
