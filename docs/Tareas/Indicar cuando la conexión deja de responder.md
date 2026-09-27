---
Nombre: 'Indicar cuando la conexión deja de responder'
Estado: 'Pendiente'
Resumen: 'Durante un microcorte corto (p. ej. activar y quitar el modo avión) la sesión sobrevive, pero la pestaña sigue mostrando "Conectado" todo el rato: el usuario no ve que la red se ha ido ni que ha vuelto. La conexión TCP no llega a romperse y el keepalive (30 s) no vence, así que la pestaña nunca pasa a RECONNECTING. Objetivo: detectar en pocos segundos que la conexión no responde (sin respuesta al keepalive, o sin ACK del agente en nivel 3) y mostrarlo en la pestaña y en la barra de estado, volviendo a "Conectado" al recuperarse, sin cortar la sesión.'
Decisiones: 'Surge de la prueba en el Pixel de [[Firma y configuración de release Android]] (2026-09-26). Complementa el estado RECONNECTING de [[Resiliencia de sesión ante microcortes de red]], que solo se activa cuando la conexión se da por caída.'
Bloqueada: []
Fecha de creación: 2026-09-26T16:52:00+02:00
Última modificación: 2026-09-28T12:45:00+02:00
---

# Indicar cuando la conexión deja de responder

## Objetivo

Que el usuario sepa, mientras ocurre, que la red se ha ido y que ha vuelto,
aunque la sesión sobreviva sin reconectar.

## Contexto

- Prueba del 2026-09-26 en el Pixel, con una sesión de nivel 3: al activar el
  modo avión unos segundos y quitarlo, la conexión se mantiene (bien), pero no
  hay ninguna indicación visual del corte. El usuario lo encontró "un poco
  extraño" y lo deja para pulir más adelante.
- `SessionTab` ya tiene `TabPhase.RECONNECTING` y `SessionsArea` lo pinta como
  `[-]` en color de aviso. Esa fase solo empieza cuando la conexión se da por
  caída; un corte corto no la rompe.
- Medido el 2026-09-28 en el emulador, al probar
  [[Reconexión que no se rinde tras un corte largo]]: con el modo avión, la
  pestaña sigue en "Conectado" unos 3 minutos (170–180 s) hasta que sshj da la
  conexión por muerta, tras 5 keepalives sin respuesta cada 30 s
  (`KeepAliveRunner`). Solo a veces cae antes, cuando Android cierra los sockets
  de la red perdida. Si la red vuelve antes, la sesión sigue como si nada. Un
  umbral más corto para *avisar* no debería adelantar la *reconexión*, porque
  eso rompería sesiones que habrían sobrevivido.

## Aplazada

El 2026-09-27 el usuario decide dejarla para más adelante: prefiere usar más
la app antes, para dar mejores indicaciones sobre el umbral y la forma del
aviso. Se retoma cuando lo pida el usuario; no bloquea nada. Propuesta de
partida, sin decidir: en nivel 3, los ACK del agente; en niveles 1 y 2, un
ping ligero propio; umbral de unos 5 s.

## Criterios de finalización

- Un estado visible del tipo "sin respuesta" (vocabulario ASCII y color de
  aviso, ver [[Vocabulario ASCII ampliado y disciplina de color]]) cuando
  pasan unos segundos sin respuesta. Hay que decidir el umbral y el mecanismo:
  keepalive de sshj más corto, un ping propio, o los ACK del agente en nivel 3.
- Vuelve a "Conectado" sola al recuperarse, sin reiniciar la sesión ni perder
  la pantalla.
- Funciona en los niveles 1, 2 y 3.
- Verificado en el Pixel con el modo avión y en escritorio cortando la red.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
