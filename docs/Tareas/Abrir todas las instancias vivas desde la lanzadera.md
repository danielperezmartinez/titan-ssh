---
Nombre: 'Abrir todas las instancias vivas desde la lanzadera'
Estado: 'Hecha'
Resumen: 'Petición del usuario (2026-10-20). Al lanzar una sesión guardada que ya tiene instancias, el menú de elección (reengancharse a cada una o nueva paralela) gana una opción "Abrir todas": abre una pestaña por cada instancia viva en el destino que no tenga pestaña, y cada una se reengancha a la suya.'
Decisiones: 'Solo se abren las instancias vivas sin pestaña: las que ya tienen pestaña no se duplican y la principal no se crea si no existe. La opción aparece cuando hay al menos dos de esas instancias (con una sola equivale a su "Reengancharse"). Se abren en orden de número y queda activa la última.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-20T10:00:00+02:00
Última modificación: 2026-10-20T11:08:00+02:00
---

# Abrir todas las instancias vivas desde la lanzadera

## Objetivo

Recuperar de una vez todas las sesiones paralelas que siguen vivas en el
destino (por ejemplo tras reiniciar la app o desde otro dispositivo), sin
tener que lanzar la sesión y reengancharse a cada instancia por separado.
Amplía el menú de [[Sesiones paralelas de una misma sesión guardada]]
([[ADR-0020 Sesiones paralelas de una misma sesión guardada]]).

## Criterios de finalización

- El menú de elección de la lanzadera muestra "Abrir todas" cuando hay dos o
  más instancias vivas en el destino sin pestaña.
- Pulsarla abre una pestaña por cada una de ellas, reenganchada a su instancia.
- No duplica las instancias que ya tienen pestaña.

## Verificación

- `SessionInstancesTest.openAllTakesTheLiveInstancesWithoutATab` (`:shared:desktopTest`) en verde.
- Emulador `Pixel_9_Pro_XL`, build de debug contra el servidor de pruebas, sesión de nivel 3: con solo la principal viva el menú no muestra la opción; con la principal y la · 2 vivas sin pestaña aparece "Abrir todas", y al pulsarla se abren dos pestañas conectadas, cada una con el historial de su instancia.

## Resultado

`LaunchPlan.Ask.background` da las instancias vivas sin pestaña, y `ReopenChoice` (lanzadera) muestra encima de los "Reengancharse" la fila "Abrir todas" cuando hay dos o más, que reengancha cada una en su pestaña.
