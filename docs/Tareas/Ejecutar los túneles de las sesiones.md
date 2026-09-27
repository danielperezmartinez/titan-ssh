---
Nombre: 'Ejecutar los túneles de las sesiones'
Estado: 'Hecha'
Resumen: 'Los túneles de una sesión (reenvío local, remoto y proxy SOCKS dinámico) ya funcionan: se abren al conectar sobre la misma conexión SSH en los tres niveles de resiliencia, se reabren tras un microcorte, se cierran al cerrar la pestaña, y la pestaña muestra cuáles están activos y cuáles han fallado y por qué ([=] túneles en la franja de estado). Los que fallan por puerto ocupado, aquí o en el servidor, se reintentan solos mientras la conexión siga viva. Verificado con tests (también contra un sshd en Docker) y en el emulador de Android. En Android, el túnel dura lo que dure la conexión de la pestaña. Después se valorará una biblioteca de túneles como plantillas.'
Decisiones: 'Sale del paso 10 de [[Seguimiento de tareas pendientes]] por decisión del usuario del 2026-09-27 ([[ADR-0013 Biblioteca de scripts unificada con los snippets]], punto 7): primero los túneles tienen que funcionar. Modelo en [[ADR-0007 Modelo y persistencia de configuración]]. El reenvío local y el SOCKS usan un bucle propio sobre canales direct-tcpip en vez del LocalPortForwarder de sshj, que no informa de las conexiones fallidas. En Android el túnel vive lo que viva la conexión de la pestaña, sin servicio en primer plano propio (decidido el 2026-09-28).'
Bloqueada: []
Fecha de creación: 2026-09-27T16:45:00+02:00
Última modificación: 2026-09-28T09:00:00+02:00
---

# Ejecutar los túneles de las sesiones

## Objetivo

Que los túneles que el usuario configura en una sesión funcionen de verdad
mientras la pestaña está abierta.

## Contexto

- El modelo (`Tunnel`, `TunnelType`: `LOCAL`, `REMOTE`, `DYNAMIC_SOCKS`) y su
  editor existen desde [[Panel de gestión de hosts y sesiones]] y
  [[Editores de script y túnel como pantalla propia]].
- Revisado el 2026-09-27: ni `SessionTab` ni el conector de sshj abrían ningún
  reenvío. Los túneles se guardaban y nada más.
- sshj tiene reenvío local (`LocalPortForwarder`) y remoto
  (`RemotePortForwarder`). El SOCKS dinámico no viene hecho: hay que
  implementarlo sobre canales `direct-tcpip`.

## Criterios de finalización

- [x] Los túneles activados se abren al conectar, sobre la conexión SSH de la
  pestaña, en los tres niveles de resiliencia (en el nivel 3 el agente no
  interviene: el túnel va por la conexión SSH, no por el PTY).
- [x] Tras un microcorte se reabren con la conexión nueva.
- [x] La pestaña muestra qué túneles están activos y cuáles han fallado, con el
  motivo (puerto local ocupado, el servidor rechaza el reenvío…), sin cortar la
  sesión.
- [x] Se cierran al cerrar la pestaña.
- [x] En Android: decidir qué pasa con el túnel cuando la app va a segundo
  plano. Decidido más abajo.
- [x] Tests del reenvío contra un sshd en Docker.

## Diseño

- **Motor** ([[SshConnector]]): `SshSession.openForward(PortForward, onProblem)`
  devuelve un `SshForward` que se cierra sin cerrar la sesión. Si el túnel no se
  puede abrir, lanza `SshForwardFailed` con el motivo (`ForwardFailure`). Cada
  conexión que pasa por un túnel abierto y no se completa llega a `onProblem`
  (el servidor no permite reenvíos, el destino no responde, petición SOCKS no
  admitida).
- **sshj** (`SshjForwards.kt`): el reenvío local y el SOCKS comparten un bucle de
  aceptación propio, que abre un canal `direct-tcpip` por cliente. No se usa el
  `LocalPortForwarder` de sshj porque solo registra en el log las conexiones
  que fallan. El remoto usa `RemotePortForwarder` con un `ConnectListener`
  propio, que avisa si el destino local no responde. El puente entre socket y
  canal respeta los cierres a medias (EOF en un sentido).
- **SOCKS**: SOCKS 4, 4a y 5, solo `CONNECT` y sin autenticación. Basta para
  navegadores, `curl` y `ProxyCommand`. Escucha en `127.0.0.1` por defecto,
  como todos los túneles.
- **Pestaña** (`SessionTunnels`, dentro de `SessionTab`): abre los túneles justo
  tras conectar, antes del agente o de la shell, y los cierra al acabar esa
  conexión. Así un microcorte los reabre con la conexión nueva. Si un túnel
  falla por puerto ocupado, aquí o en el servidor, se reintenta mientras dure la
  conexión: cada 5 s el primer minuto y después cada 30 s. Hace falta tras un
  microcorte, porque el servidor puede retener el puerto de un túnel remoto
  con la conexión vieja hasta que detecta el corte. En la prueba, el remoto
  volvió a los 12 s.
- **UI** ([[TerminalView]]): la franja de estado muestra `[=] túneles 4/5`
  (activos/total), en color de aviso si alguno falla. Al pulsarlo se abre un
  panel con el estado de cada túnel y el motivo.

## Android en segundo plano

Decidido el 2026-09-28: el túnel vive lo mismo que la conexión SSH de la
pestaña, sin nada propio. La app aún no tiene un servicio en primer plano para las sesiones. Si
Android corta la red de la app en segundo plano, la conexión cae y, al volver,
la pestaña reconecta y reabre los túneles como tras un microcorte. Un servicio
en primer plano mantendría vivas las sesiones y sus túneles con la app oculta,
pero afectaría a todas las sesiones, no solo a los túneles, así que sería una
tarea aparte.

## Después

Valorar una biblioteca de túneles como plantillas (se pidió en
[[Scripts y túneles reutilizables de primera clase]]): los puertos y destinos
suelen ser propios de cada sesión.

## Verificación

- 192 tests de escritorio en verde. Son nuevos `SocksTest` (el protocolo SOCKS y
  la clasificación de los fallos al escuchar) y `SessionTabTunnelsTest`. Este
  comprueba que se abren al conectar (solo los activados), que el fallo de uno
  no corta la sesión, que se reabren tras un corte, que se reintentan hasta
  abrirse y que se cierran con la pestaña.
- `SshjForwardingIntegrationTest` contra el servidor de pruebas de
  `tools/test-sshd/`: reenvío local, SOCKS (con el cliente SOCKS del JDK) y
  remoto; 8 MB por un túnel sin perder bytes y con el EOF; puerto local ocupado;
  puerto remoto ocupado. Con `AllowTcpForwarding no` (Alpine lo trae así de
  serie) comprueba también los rechazos. El servidor de pruebas pasa a tener
  el reenvío activado.
- Emulador `Pixel_9_Pro_XL`, build de debug, sesión con cinco túneles contra el
  servidor de pruebas. Pasa tráfico real por el local, el SOCKS y el remoto. El
  de puerto repetido falla con su motivo y el de destino sin servicio avisa en
  cuanto se usa. Tras un corte con modo avión, la pestaña reconecta y reabre
  todos, también el remoto, gracias al reintento. Al cerrar la pestaña se
  liberan los puertos.
- Hallazgo ajeno a los túneles: con 40 s de modo avión la pestaña agota sus 6
  reintentos (unos 25 s de espera en total, según `ReconnectPolicy.Default`) y
  se queda caída.

## Resultado

Hecha el 2026-09-28. Los túneles funcionan en escritorio y en Android. La
prueba en el emulador cubre la verificación en dispositivo (paso 0 de la regla
4 del [[README]]); el emulador queda abierto con la sesión de ejemplo por si el
usuario quiere probarla. Sale en la siguiente pre-release.
