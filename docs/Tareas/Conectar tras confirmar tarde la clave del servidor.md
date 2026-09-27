---
Nombre: 'Conectar tras confirmar tarde la clave del servidor'
Estado: 'Hecha'
Resumen: 'La primera vez que se conecta a un servidor, la app pregunta si se confía en su clave. Antes, si la pregunta seguía abierta más de 30 s (por ejemplo, mientras se comprobaba la huella), el saludo SSH caducaba y la pestaña acababa en "Could not connect". Ahora la pregunta sigue abierta aunque la conexión caduque: al aceptarla, la pestaña guarda la clave y vuelve a conectar sola; al rechazarla, dice "Clave de host rechazada". Verificado con tests, contra el servidor de pruebas y en el emulador.'
Decisiones: 'Sale de la nota suelta Revisar.md, un hallazgo de un agente al probar [[Ejecutar los túneles de las sesiones]] (2026-09-28). Se hace junto a [[Reconexión que no se rinde tras un corte largo]], por decisión del usuario del 2026-09-28. TOFU según [[ADR-0005 Autenticación SSH y verificación de host]].'
Bloqueada: []
Fecha de creación: 2026-09-28T10:30:00+02:00
Última modificación: 2026-09-28T12:30:00+02:00
---

# Conectar tras confirmar tarde la clave del servidor

## Objetivo

Que comprobar la huella del servidor con calma no rompa la primera conexión.

## Contexto

- Hallazgo de un agente al probar los túneles: si la pregunta de la clave de
  host se queda abierta un par de minutos, la conexión falla con "Could not
  connect". Aceptando al momento funciona.
- Causa: `SshjConnector` pregunta dentro del saludo SSH (el verificador de
  sshj se queda esperando la respuesta en el hilo lector del transporte).
  Mientras tanto vence el tiempo del intercambio de claves de sshj (30 s por
  defecto) y, si no, el `LoginGraceTime` del servidor (120 s en OpenSSH).
  `connect()` falla con `SshConnectFailed`, la pestaña pasa a `FAILED` y, si
  el usuario acepta después, la clave se guarda pero la conexión ya está
  perdida.

## Criterios de finalización

- Si la conexión caduca mientras la pregunta está abierta, la pestaña sigue en
  "Conectando…" con la pregunta visible.
- Al aceptar, la pestaña vuelve a conectar sola y no vuelve a preguntar (la
  clave ya está guardada).
- Al rechazar, la pestaña queda en `FAILED` con "Clave de host rechazada", no
  con "Could not connect".
- Cerrar la pestaña con la pregunta abierta la rechaza, para no dejar el hilo
  de sshj bloqueado.
- Cubierto con tests y comprobado en la app contra el servidor de pruebas.

## Diseño

- La respuesta del usuario vive en `SessionTab`, no en el verificador. sshj
  interrumpe su hilo lector cuando caduca el saludo, lo que cancelaba la espera
  y quitaba la pregunta de la pantalla. Ahora la pregunta solo desaparece
  cuando el usuario responde.
- Si `connect()` falla con `SshConnectFailed` y en ese intento se había
  preguntado, la pestaña espera la respuesta. Si es aceptar, guarda la clave
  (`KnownHostsStore.trust`, que no duplica una entrada ya guardada) y vuelve a
  conectar al momento (`TRUSTED_LATE`); si es rechazar, `FAILED` con "Clave de
  host rechazada".
- `close()` rechaza la pregunta abierta, para liberar el hilo de sshj.

## Verificación

- Tests: `SessionTabHostKeyTest` (5 casos, con un conector falso que imita a
  sshj con y sin interrupción del verificador: aceptar tarde, rechazar tarde,
  aceptar a tiempo y cerrar con la pregunta abierta). Suite de escritorio
  completa: 203 tests en verde.
- Test de integración contra el servidor de pruebas (`SessionTabIntegrationTest`,
  opcional): con la pregunta abierta 40 s, el primer intento muere (sshj corta
  a los 30 s), la pregunta sigue en pantalla y, al aceptar, un segundo intento
  conecta sin volver a preguntar y guarda la clave una sola vez.
- Emulador `Pixel_9_Pro_XL`, build de debug, sesión "proyecto demo" sin la
  clave guardada: tras 45 s con la pregunta abierta la pestaña sigue en
  "Conectando…" con la pregunta visible; al pulsar **Confiar** conecta sola
  (nivel 1, cuatro de cinco túneles como antes) y `known_hosts` queda con una
  sola entrada.

## Resultado

Hecha el 2026-09-28. Comprobar la huella con calma ya no rompe la primera
conexión.
