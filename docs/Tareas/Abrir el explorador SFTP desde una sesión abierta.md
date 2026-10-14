---
Nombre: 'Abrir el explorador SFTP desde una sesión abierta'
Estado: 'Pendiente'
Resumen: 'Petición del usuario (2026-10-14), para más adelante. Además del tipo de sesión SFTP, poder abrir el explorador de archivos desde una pestaña de terminal ya conectada, sobre su misma conexión SSH, sin crear una sesión guardada de tipo SFTP. El usuario quiere que esta acción se abra desde el menú de acciones de la pestaña que nacerá con [[Renombrar las pestañas y las sesiones activas]].'
Decisiones: 'Sale de [[Sesión SFTP para navegar y descargar archivos]] y de [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]], que solo cubren el tipo de sesión. Recordatorio del usuario: abordarla junto con [[Renombrar las pestañas y las sesiones activas]], porque el punto de entrada es el mismo menú de la pestaña y las decisiones de ese menú se toman a la vez.'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-14T10:50:00+02:00
---

# Abrir el explorador SFTP desde una sesión abierta

## Objetivo

Desde una pestaña de terminal conectada, abrir el explorador SFTP del mismo
destino reutilizando su conexión, sin tener que crear ni lanzar una sesión
guardada de tipo SFTP.

## Por decidir al empezarla

- **Punto de entrada**: el usuario quiere que sea una acción del menú de la
  pestaña que se diseñe en [[Renombrar las pestañas y las sesiones activas]].
  Al abordar cualquiera de las dos tareas, revisar la otra y decidir ese menú
  de una vez (renombrar, abrir en paralelo, abrir explorador…).
- Si el explorador se abre en una pestaña nueva o en un panel dentro de la
  misma pestaña.
- Si comparte la conexión SSH de la terminal (un canal SFTP más sobre la
  misma sesión) y qué pasa con él cuando la terminal reconecta.
- Si arranca en la carpeta actual de la terminal (habría que conocerla) o en
  la de inicio.

## Criterios de finalización

- Desde una pestaña de terminal se abre el explorador del mismo destino.
- Probado en el emulador y en escritorio.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
