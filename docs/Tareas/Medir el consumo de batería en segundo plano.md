---
Nombre: 'Medir el consumo de batería en segundo plano'
Estado: 'Por probar'
Estado desde: 2026-10-14
Resumen: 'El usuario nota que el móvil gasta más batería con varias sesiones abiertas y la pantalla bloqueada en el bolsillo, y sospecha que la app reconecta sin parar en segundo plano. Se mide antes de cambiar nada: en el emulador (Doze forzado, cortes de red, conexiones vistas desde el servidor de pruebas) y en el Pixel con batterystats y los inicios de sesión del destino.'
Decisiones: 'Primero medir y después decidir el arreglo (decisión del usuario del 2026-10-14). Se empieza por el emulador; el usuario hace la medición en el Pixel con su uso real. El emulador confirmó la causa y el arreglo se decidió en [[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]], con su implementación en [[Conexiones en segundo plano según lo que pueda perder cada pestaña]]; la medición del Pixel sirve de referencia para comparar.'
Bloqueada: []
Prueba: 'Medir en el Pixel el consumo de una noche con sesiones abiertas frente a una noche sin sesiones.'
Esperando a: ''
Fecha de creación: 2026-10-14T12:00:00+02:00
Última modificación: 2026-10-28T12:00:00+01:00
---

# Medir el consumo de batería en segundo plano

## Objetivo

Saber si titan-ssh gasta batería con la pantalla bloqueada y varias sesiones
abiertas, y en qué: reconexiones, keepalives o temporizadores. Con eso se
decide el arreglo.

## Contexto

En Android no hay servicio en primer plano ni wakelocks. Mientras el sistema no
congele el proceso, por cada pestaña abierta:

- Keepalive SSH cada 30 s (`keepAliveSeconds`), uno más por cada salto.
- Tras un corte, sshj tarda unos 3 min en dar la conexión por muerta y la
  pestaña reintenta cada 10 s durante 15 min, con hasta 15 s de conexión TCP
  por intento ([[Reconexión que no se rinde tras un corte largo]], desde el
  2026-09-28).
- La vuelta de la red (`networkRestored`) relanza también las pestañas que ya
  se habían rendido: cada cambio de red reinicia el ciclo en todas.
- `SshjSession` sondea `isConnected` cada 1 s; los túneles que fallan se
  reintentan cada 5 s y luego cada 30 s.

## Plan de medición

1. Emulador `Pixel_9_Pro_XL` contra `tools/test-sshd/`: varias sesiones,
   pantalla apagada, Doze forzado (`dumpsys deviceidle force-idle`), cortes de
   red. Se observa si el proceso queda congelado, las conexiones que recibe el
   servidor, el tiempo de CPU y las alarmas o wakeups.
2. Pixel con uso real (lo hace el usuario): `dumpsys batterystats --reset`,
   1–2 h en el bolsillo, `batterystats --charged` del paquete y `bugreport`
   para Battery Historian; la misma prueba sin sesiones como referencia.
3. En el destino real: contar los inicios de sesión SSH durante ese rato.

## Cómo probarlo

1. **Noche con sesiones**: la prueba de
   [[Conexiones en segundo plano según lo que pueda perder cada pestaña]]
   (sus pasos 1 a 4), con las pestañas que se usan normalmente.
2. **Noche de referencia**: los mismos pasos sin ninguna pestaña abierta en
   titan-ssh.
3. Pasar al agente, de las dos noches, el porcentaje de batería gastado, el
   uso por app y, si se pudo, `batterystats.txt`.

Con esos datos el agente escribe la conclusión y, si hay ineficiencia, la
tarea de arreglo.

## Resultados en el emulador (2026-10-14)

Emulador `Pixel_9_Pro_XL` (Android 15), build de debug de `main`, dos
pestañas (una de nivel 1 con túneles y otra de nivel 3) contra un sshd de
medición aparte (misma imagen que `tools/test-sshd/`). Se muestreó cada 20–30 s
el estado del proceso (`dumpsys activity processes`), el bloqueo de red
(`dumpsys netpolicy`), la CPU (`/proc/<pid>/stat`), los sockets hacia el
destino y las conexiones aceptadas por el sshd. Cada intento de conexión deja
una línea `TrafficStats: tagSocket` del PID de la app en logcat.

**Android corta la red a la app en segundo plano, pero no la congela.**

- Pantalla apagada con la app delante (el caso del usuario): el proceso pasa
  a `TOP_SLEEPING` (procState 12). Android 15 bloquea su red
  (`blocked=DOZE|APP_BACKGROUND`; ajuste
  `network_blocked_for_top_sleeping_and_above: true`), pero el congelador
  solo actúa sobre procesos *cached*, así que la app sigue ejecutándose.
- App en segundo plano con la pantalla encendida: procState 15
  (`LAST_ACTIVITY`), mismo bloqueo (`APP_BACKGROUND`), sin congelar en 10 min.
- En ambos casos las sesiones se pierden al momento (el sshd no ve
  ninguna conexión establecida) y empieza el bucle de reconexión contra una red
  bloqueada: los SYN no salen y cada intento agota los 15 s de conexión TCP
  más 10 s de backoff.

Cifras, con dos pestañas:

| Situación | Duración | Intentos de conexión | CPU de la app |
| --- | --- | --- | --- |
| Segundo plano, pantalla encendida | ~11 min | 76 | ~4 s |
| Pantalla apagada, app delante | 15 min hasta rendirse | 78 | ~3,7 s |
| Tras rendirse (plazo de 15 min) | 2 min | 0 | 0 |

- Al vencer el plazo de [[Reconexión que no se rinde tras un corte largo]]
  los intentos paran y la CPU queda plana. Cualquier aviso de red por defecto
  (`onAvailable`: cambio Wi-Fi ↔ datos) relanza otro ciclo de 15 min en todas
  las pestañas, también en las que se habían rendido.
- `batterystats` del emulador no atribuye consumo apreciable a la app: el
  emulador nunca suspende la CPU, así que no sirve para medir el coste real
  en energía. Eso queda para el Pixel.
- Al volver al frente, la red se desbloquea y las pestañas reconectan en
  segundos (las de nivel 1 pierden el shell; las de nivel 3 recuperan el PTY
  del agente).

Conclusión provisional: la sospecha se confirma. En segundo plano la app no
puede mantener las sesiones con la red bloqueada y gasta CPU durante 15 min por
cada corte o cambio de red en intentos que no pueden salir. Que eso explique el
consumo observado en el Pixel lo dirá la medición real.

## Criterios de finalización

- Se sabe si la app reconecta en segundo plano y con qué frecuencia.
- Se sabe qué parte del consumo es de la app (CPU, radio) frente a la
  referencia sin sesiones.
- Si hay ineficiencia, queda una tarea de arreglo enlazada desde aquí.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
