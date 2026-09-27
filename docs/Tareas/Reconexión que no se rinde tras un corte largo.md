---
Nombre: 'Reconexión que no se rinde tras un corte largo'
Estado: 'Hecha'
Resumen: 'Antes, tras un corte la pestaña hacía 6 reintentos (unos 25 s) y se rendía, y para recuperarla había que cerrarla y perder el historial. Ahora reintenta durante 15 minutos con backoff hasta 10 s, en Android reconecta en cuanto vuelve la red (aviso de ConnectivityManager), y la franja de estado ofrece "reconectar" para no esperar o para recuperar una pestaña caída o fallida en la misma pestaña, con su historial. El conector fija 15 s de tiempo de conexión TCP. Verificado con tests y en el emulador con el modo avión.'
Decisiones: 'Sale del hallazgo de la verificación de [[Ejecutar los túneles de las sesiones]] (2026-09-27). Se hace junto a [[Conectar tras confirmar tarde la clave del servidor]], por decisión del usuario del 2026-09-28. Amplía la resiliencia de nivel 1 de [[Resiliencia de sesión ante microcortes de red]] (ADR-0003). No es la detección rápida de una conexión que no responde, que sigue aparcada en [[Indicar cuando la conexión deja de responder]].'
Bloqueada: []
Fecha de creación: 2026-09-28T10:30:00+02:00
Última modificación: 2026-09-28T13:00:00+02:00
---

# Reconexión que no se rinde tras un corte largo

## Objetivo

Que la pestaña sobreviva a cortes de red de minutos (un túnel, un ascensor, el
metro), que es el primer pilar del producto, y que nunca haga falta cerrarla
para volver a conectar.

## Contexto

- Prueba del paso 10b en el emulador: con 40 s de modo avión la pestaña agota
  sus 6 reintentos (`ReconnectPolicy.Default`: 0,5 + 1 + 2 + 4 + 8 + 8 s) y
  queda en `DISCONNECTED`.
- En `DISCONNECTED` o `FAILED` la pestaña no ofrece ninguna acción: hay que
  cerrarla y abrir la sesión de nuevo, con lo que se pierde el historial de la
  pantalla (en nivel 3 el agente conserva el PTY, pero la pestaña nueva
  empieza de cero).
- `SshjConnector` no fija tiempo de conexión TCP: con red pero sin ruta al
  servidor, un intento puede tardar lo que diga el sistema (hasta unos 2 min
  en Linux y Android).

## Diseño

- `ReconnectPolicy` pasa de un número de intentos a un **plazo**: se reintenta
  durante 15 minutos desde el corte (`giveUpAfterMillis`), con backoff
  exponencial hasta 10 s. `maxAttempts` queda como límite opcional (sin
  límite por defecto).
- **Vuelta de la red**: en Android, un `ConnectivityManager.NetworkCallback`
  avisa cuando hay red por defecto. Una pestaña que está esperando entre
  reintentos lo intenta ya; una que se rindió tras el plazo vuelve a
  intentarlo. No se tocan las pestañas cerradas por salida limpia ni las que
  fallaron al conectar por primera vez. En escritorio no hay aviso (se queda
  el backoff de 10 s).
- **Acción "reconectar"** en la franja de estado: en `RECONNECTING` corta la
  espera; en `DISCONNECTED` o `FAILED` vuelve a lanzar la conexión en la
  misma pestaña, conservando el emulador (pantalla e historial).
- `SshjConnector` fija un tiempo de conexión TCP de 15 s.

## Criterios de finalización

- Un corte de 40 s o de varios minutos no deja la pestaña caída: reconecta
  sola al volver la red.
- En Android reconecta en cuanto vuelve la red, sin esperar al backoff.
- Pasado el plazo, la pestaña queda `DISCONNECTED` con el motivo y la acción
  "reconectar", que recupera la sesión sin perder el historial.
- Cubierto con tests y comprobado en el emulador con el modo avión.

## Verificación

- Tests: `SessionTabReconnectTest` pasa de 4 a 10 casos. Cubren: sigue más allá
  de seis intentos dentro del plazo; se rinde al agotar el plazo; "reconectar"
  corta la espera; "reconectar" recupera una pestaña que se rindió, con su
  historial, y una que falló al conectar por primera vez; la vuelta de la red
  reintenta solo las pestañas cortadas. Suite de escritorio completa: 203
  tests en verde. APK de debug compilada.
- Emulador `Pixel_9_Pro_XL`, build de debug, sesión "proyecto demo" (nivel 1)
  contra el servidor de pruebas:
  - 2,5 minutos en modo avión: 19 intentos sin rendirse y la franja con
    "Reconectando… (intento N)" y **reconectar**. Al quitar el modo avión,
    conecta en unos 4 s con el historial intacto.
  - Tres cortes deterministas (se para el servidor para que la pestaña vea el
    corte al momento, luego modo avión y se vuelve a arrancar el servidor):
    con el backoff ya en 10 s, las tres reconectan unos 5,2–5,3 s después de
    quitar el modo avión, siempre igual. Con el backoff solo, el tiempo
    variaría entre 0 y 10 s, así que lo despierta el aviso de red. De esos
    5 s, unos 2 s son lo que tarda el emulador en recuperar la red, y el resto
    la conexión y la lectura de la pantalla.
  - Pulsar **reconectar** con la pestaña esperando lanza un intento al
    momento (intento 8 → 10 en 1 s, sin red).
- No probado en el emulador: el plazo de 15 minutos y "reconectar" tras
  rendirse. Están cubiertos por los tests con reloj virtual.

## Hallazgo

Con el modo avión, la pestaña sigue en "Conectado" unos 3 minutos hasta que
sshj da la conexión por muerta (5 keepalives de 30 s sin respuesta). Solo
entonces empieza la reconexión. Si la red vuelve antes, la sesión sigue sin
reconectar. Avisar antes de eso es trabajo de
[[Indicar cuando la conexión deja de responder]] (aparcada), donde queda
anotado.

## Resultado

Hecha el 2026-09-28. Tras un corte, la pestaña reintenta durante 15 minutos
(backoff hasta 10 s) y, en Android, reconecta en cuanto vuelve la red. La
franja ofrece **reconectar** para no esperar o para recuperar una pestaña
caída o fallida sin perder el historial. `SshjConnector` fija 15 s de tiempo
de conexión TCP.
