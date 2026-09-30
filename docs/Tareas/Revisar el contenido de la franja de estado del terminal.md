---
Nombre: 'Revisar el contenido de la franja de estado del terminal'
Estado: 'Pendiente'
Resumen: 'Revisión para más adelante, pedida por el usuario el 2026-09-30. La franja de encima del terminal debe quedarse solo con el estado de la conexión, el nivel de resiliencia y el acceso a los scripts (Franja de estado del terminal solo para el estado y los scripts). Hoy tiene además la acción reconectar, los túneles y el aviso del nivel 3 debajo. Hay que decidir con el usuario qué se queda, qué se mueve a otra pantalla y a cuál. No se toca hasta entonces.'
Decisiones: 'Sigue [[Franja de estado del terminal solo para el estado y los scripts]]. El usuario no quiere cambiar nada ahora (2026-09-30).'
Bloqueada: []
Fecha de creación: 2026-09-30T21:15:00+02:00
Última modificación: 2026-09-30T21:15:00+02:00
---

# Revisar el contenido de la franja de estado del terminal

## Objetivo

Dejar la franja de estado de [[TerminalView]] como fija
[[Franja de estado del terminal solo para el estado y los scripts]]: el estado
de la conexión, el nivel de resiliencia y `[>] scripts`.

## Qué revisar

Lo que tiene hoy fuera de la regla, con dónde podría ir cada cosa (a decidir
con el usuario):

- **`reconectar`** en `Reconectando…`, `Caída` y `Fallo`
  ([[Reconexión que no se rinde tras un corte largo]]). Es una acción ligada
  al estado; hay que decidir si cuenta como parte de él o se mueve.
- **`[=] túneles activos/total`** y su panel
  ([[Ejecutar los túneles de las sesiones]]).
- **El aviso del nivel 3** debajo de la franja, con `activar linger` y `[x]`
  ([[Diagnóstico cuando el nivel 3 no está disponible]]).

## Criterios de finalización

- El usuario decide qué hacer con cada elemento.
- Si algo se mueve, su nueva pantalla lo muestra y sus acciones siguen al
  alcance. Se prueba en el emulador y en escritorio.
- Las decisiones visuales afectadas se actualizan (reemplazándolas si cambia
  lo decidido) y también el catálogo de [[TerminalView]].

## Verificación

## Resultado
