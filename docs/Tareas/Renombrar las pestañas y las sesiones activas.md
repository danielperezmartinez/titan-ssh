---
Nombre: 'Renombrar las pestañas y las sesiones activas'
Estado: 'Pendiente'
Resumen: 'Petición del usuario (2026-10-13), para más adelante. Con las sesiones paralelas, las instancias de una misma sesión guardada se llaman <nombre> · 2, · 3…; el usuario quiere poder ponerles un nombre propio (por ejemplo "logs") a las pestañas y a las sesiones vivas en el destino, para reconocerlas en la lanzadera, en el panel del agente y desde otro dispositivo. Necesita un menú de acciones en la pestaña, que hoy no existe; en ese menú también encaja abrir una paralela desde la propia pestaña.'
Decisiones: 'Sale de [[Sesiones paralelas de una misma sesión guardada]] y de [[ADR-0020 Sesiones paralelas de una misma sesión guardada]], que de momento numeran las paralelas. Dónde se guarda el nombre (en la app o en el agente, para verlo desde otros dispositivos) se decide con el usuario al empezarla. Recordatorio del usuario (2026-10-14): el mismo menú de la pestaña debe abrir el explorador SFTP de [[Abrir el explorador SFTP desde una sesión abierta]]; decidir ese menú pensando en las dos tareas.'
Bloqueada: []
Fecha de creación: 2026-10-13T09:45:00+02:00
Última modificación: 2026-10-14T10:50:00+02:00
---

# Renombrar las pestañas y las sesiones activas

## Objetivo

Que el usuario pueda dar un nombre propio a una pestaña abierta y a la
instancia de la sesión del destino que muestra (principal o paralela), en vez
de `<nombre> · 2`, y que ese nombre se vea en la pestaña, en la lanzadera y en
el panel del agente.

## Por decidir al empezarla

- **Dónde vive el nombre**: solo en la app (no se ve desde otro dispositivo) o
  en el agente del destino (se ve desde todos, pero exige una orden nueva en el
  agente y no cubre el nivel 2).
- **Desde dónde se renombra**: un menú de acciones en la pestaña (hoy la
  pulsación larga de la pestaña sirve para reordenarla), la lanzadera o el
  panel del agente.
- Si el mismo menú de la pestaña ofrece también "Abrir en paralelo", que
  quedó fuera de [[Sesiones paralelas de una misma sesión guardada]].
- Que ese menú abra también el explorador SFTP del destino: el usuario lo
  quiere ahí ([[Abrir el explorador SFTP desde una sesión abierta]]).

## Criterios de finalización

- Se puede renombrar una pestaña o instancia abierta y el nombre se ve en la
  pestaña, la lanzadera y el panel del agente.
- El nombre sobrevive a una reconexión.
- Probado en el emulador y en escritorio.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
