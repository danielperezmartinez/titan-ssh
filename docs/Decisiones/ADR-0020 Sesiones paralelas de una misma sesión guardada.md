---
Nombre: 'Sesiones paralelas de una misma sesión guardada'
Número: 20
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-13. Hasta ahora una sesión guardada con nivel 2 o 3 tenía una sola sesión en el destino: abrirla dos veces enganchaba las dos pestañas a la misma terminal, y para tener dos terminales independientes había que duplicar la sesión guardada. Se decide que una sesión guardada pueda tener varias instancias en el destino: la principal (con el id de siempre) y paralelas numeradas · 2, · 3… (id <sesión>-p<n>, el número libre más bajo). Al lanzar una sesión que ya tiene instancias abiertas o vivas, la app pregunta si reengancharse a una de ellas o abrir una paralela; un ajuste por sesión guardada permite saltarse la pregunta (preguntar · reengancharse siempre · paralela siempre). El agente no cambia.'
Decisión: 'Una sesión guardada de nivel 2 o 3 puede tener varias instancias en el destino, la principal y paralelas numeradas; el usuario elige al lanzarla si se reengancha o abre una paralela, salvo que el ajuste de la sesión diga otra cosa.'
Consecuencias: 'Dos terminales independientes ya no exigen duplicar la sesión guardada, y se conserva el reenganche tras una caída, un reinicio de la app o desde otro dispositivo, también para las paralelas, porque su número es estable. La app tiene que agrupar las instancias bajo su sesión guardada en la lanzadera y en el panel del agente, y terminar o eliminar una sesión guardada afecta a todas sus instancias. Las paralelas también se acumulan hasta que el usuario las cierra (ADR-0014).'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-13T09:45:00+02:00
Última modificación: 2026-10-13T09:45:00+02:00
---

# ADR-0020 · Sesiones paralelas de una misma sesión guardada

## Contexto

En la tarea [[Transparencia y control del agente en el destino]]
(2026-09-29) se confirmó con el usuario que hubiera una sesión del agente por
sesión guardada: el id de la sesión del agente es el de la sesión guardada
(`SessionTab`: `agentSessionId = resolved.session.id`), y en nivel 2 el nombre
de tmux/screen es `titan-<id de la sesión guardada>`. Abrir la misma sesión
guardada en dos pestañas mostraba la misma terminal en las dos, y para tener
dos terminales independientes había que usar dos sesiones guardadas.

Ese modelo sirve para reengancharse sin perder trabajo, pero obliga a duplicar
la configuración para algo tan habitual como abrir una segunda terminal en el
mismo proyecto. El 2026-10-13 el usuario pide que sea él quien decida, al
abrirla, si se reengancha o abre una sesión paralela.

En nivel 1 cada pestaña ya es una shell propia; ahí no hay nada que decidir.

## Decisión

Decidido con el usuario el 2026-10-13:

1. **Instancias.** Una sesión guardada de nivel 2 o 3 puede tener varias
   instancias en el destino:
   - la **principal**, con el id de siempre (las sesiones vivas de antes de
     este cambio siguen siendo la principal);
   - **paralelas** numeradas desde el 2, con id `<id de la sesión
     guardada>-p<n>`. En el agente es `titan-<…>-p<n>` y en tmux/screen,
     `titan-<…>-p<n>`.
2. **Número estable, no aleatorio.** Una paralela nueva toma el número libre
   más bajo entre las instancias que la app conoce (pestañas abiertas y la
   última consulta al agente). Así se puede volver a ella tras una caída, tras
   reiniciar la app o desde otro dispositivo, igual que a la principal. Si al
   conectar resulta que ese número ya estaba vivo en el destino (la consulta
   estaba desfasada), la pestaña prueba con el siguiente: una paralela nueva
   nunca se engancha a una terminal existente.
3. **Elección al lanzar.** Si la sesión guardada ya tiene instancias abiertas
   en pestañas o vivas en el destino, la lanzadera ofrece reengancharse a cada
   una o abrir una paralela nueva. Si no tiene ninguna, se abre la principal
   sin preguntar, como antes.
4. **Ajuste por sesión guardada** "Al abrirla de nuevo": `Preguntar` (por
   defecto) · `Reengancharse` (a la principal) · `Abrir una paralela`.
5. **Nombre en la pestaña y en las listas**: `<nombre> · 2`. Renombrar
   pestañas o instancias queda para la tarea
   [[Renombrar las pestañas y las sesiones activas]].
6. **Cerrar.** Cerrar la pestaña de una paralela no pierde trabajo, como con
   la principal. "Terminar en el destino" y eliminar la sesión guardada
   terminan todas sus instancias; el panel del agente permite cerrar una
   concreta.
7. **El agente no cambia**: el id de sesión del `HELLO` ya es una cadena
   cualquiera ([[ADR-0008 Diseño del agente de resiliencia nivel 3]]).

## Alternativas consideradas

- **Mantener una sola sesión por sesión guardada y duplicar la sesión
  guardada** para tener otra terminal: descartada por el usuario, obliga a
  mantener configuraciones repetidas.
- **Id aleatorio por pestaña**: descartada. Las pestañas no se guardan al
  cerrar la app, así que una paralela quedaría huérfana para siempre y no se
  podría recuperar desde otro dispositivo.
- **Que la paralela la nombre el usuario al crearla**: aplazada. De momento se
  numeran, y el renombrado va en
  [[Renombrar las pestañas y las sesiones activas]].
- **Preguntar siempre**, aunque no haya nada vivo: descartada, añade un paso
  sin elección real.

## Consecuencias

- Positivas: dos terminales independientes sin duplicar la configuración; el
  reenganche se mantiene para todas las instancias.
- Negativas / compromisos: la lanzadera y el panel del agente tienen que
  agrupar las instancias bajo su sesión guardada. Las paralelas se acumulan
  hasta que el usuario las cierra (ADR-0014). Si se elimina la sesión guardada
  con el destino sin responder, solo se cierran en la siguiente conexión las
  instancias que la app conocía; una paralela creada desde otro dispositivo
  después de la última consulta aparece como huérfana en el panel del agente.
- Cambia el acuerdo de [[Transparencia y control del agente en el destino]]
  ("para dos terminales independientes se usan dos sesiones guardadas"). Se
  implementa en [[Sesiones paralelas de una misma sesión guardada]].
