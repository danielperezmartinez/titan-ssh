---
Nombre: 'Sesiones del agente sin caducidad'
Número: 14
Estado: 'Aceptada'
Resumen: 'Las sesiones del agente de nivel 3 no caducan: se quita el TTL de 30 minutos que cerraba las sesiones sin cliente conectado (GC de ADR-0008 §6), y el daemon tampoco se cierra solo al quedarse sin sesiones. Una sesión solo termina cuando su shell sale o cuando el usuario la cierra de forma explícita. A cambio, la app avisa al usuario cuando el agente acumula sesiones o memoria, y le da la forma de verlas y cerrarlas.'
Decisión: 'Quitar la caducidad por tiempo de las sesiones del agente y no añadir ningún cierre automático del daemon. Los recursos se controlan con avisos al usuario y cierres explícitos, nunca borrando trabajo en silencio.'
Consecuencias: 'Se cumple el pilar de no perder trabajo pase el tiempo que pase. Las sesiones olvidadas se acumulan hasta que el usuario las cierra, así que la app tiene que mostrarlas, avisar y permitir cerrarlas: pasa a ser necesario, no opcional. El buffer de cada sesión sigue acotado por bytes, así que lo que crece es el número de sesiones y lo que corre dentro de ellas.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-29T21:30:00+02:00
Última modificación: 2026-09-29T21:30:00+02:00
---

# ADR-0014 · Sesiones del agente sin caducidad

## Contexto

[[ADR-0008 Diseño del agente de resiliencia nivel 3]] (§6, GC) decidió que el
agente expira las sesiones ociosas por TTL. En el código es
`Registry.GC(sessionTTL)` con `sessionTTL = 30 * time.Minute`: una sesión sin
cliente conectado cuyo último uso pasó hace más de 30 minutos se cierra, aunque
dentro siga corriendo un proceso.

Al plantear la tarea [[Transparencia y control del agente en el destino]]
(2026-09-29) se vio que esto choca con el primer pilar del producto (no perder
el trabajo ante cortes, sea cual sea su duración). El usuario descartó además
que el daemon se cierre solo cuando se queda sin sesiones.

## Decisión

Decidido con el usuario el 2026-09-29:

1. **Sin caducidad por tiempo.** Se quita el TTL de las sesiones. El GC solo
   retira las sesiones cuya shell ya terminó.
2. **El daemon no se cierra solo**, tenga o no sesiones.
3. **Cerrar es siempre explícito**: el usuario cierra una sesión o detiene el
   agente desde la app o desde el propio agente (tarea
   [[Transparencia y control del agente en el destino]]).
4. **Avisar en vez de borrar.** Si el agente acumula sesiones o memoria, la app
   avisa al usuario y le lleva a donde puede cerrarlas. Qué dispara el aviso y
   dónde se muestra se concreta en la tarea.
5. Se mantiene el límite en bytes del buffer de cada sesión: acota la memoria
   del propio agente sin perder la sesión (solo se pierde el historial más
   antiguo).

## Alternativas consideradas

- **Mantener el TTL de 30 minutos**: descartada, cierra trabajo vivo tras un
  corte largo o al dejar una sesión en segundo plano.
- **Un TTL más largo (horas o días)**: descartada por lo mismo; solo retrasa el
  problema.
- **Cerrar el daemon cuando se queda sin sesiones**: descartada por el usuario.

## Consecuencias

- Las sesiones olvidadas se acumulan hasta que el usuario las cierra. Por eso
  la app tiene que mostrarlas, avisar y permitir cerrarlas.
- Sustituye en parte el §6 (GC) de ADR-0008. El resto de ADR-0008 sigue
  vigente.
