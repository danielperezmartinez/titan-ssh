---
Nombre: 'Indicar cuando la conexión deja de responder'
Estado: 'Por probar'
Estado desde: 2026-10-28
Resumen: 'Durante un microcorte corto (p. ej. activar y quitar el modo avión) la sesión sobrevive, pero la pestaña sigue mostrando "Conectado" todo el rato: el usuario no ve que la red se ha ido ni que ha vuelto. La conexión TCP no llega a romperse y el keepalive (30 s) no vence, así que la pestaña nunca pasa a RECONNECTING. Objetivo: detectar en pocos segundos que la conexión no responde o va lenta con una sonda SSH solo en primer plano y mostrarlo con la barra de calidad de tres fragmentos en las franjas de estado del terminal, del SFTP y del mouse pad, volviendo a "Conectado" al recuperarse, sin cortar la sesión ni cambiar cuándo se reconecta. Las pestañas no cambian.'
Decisiones: 'Surge de la prueba en el Pixel de [[Firma y configuración de release Android]] (2026-09-26). Complementa el estado RECONNECTING de [[Resiliencia de sesión ante microcortes de red]], que solo se activa cuando la conexión se da por caída. El usuario fija el 2026-10-28 que es solo un indicador: no cambia cuándo se reconecta, solo detecta con la app en primer plano y la pantalla encendida, y la sonda SSH solo sale con la conexión callada, para no contradecir [[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]] ni [[Alinear los keepalives de las pestañas]]. Se diseñó junto a [[Revisar el contenido de la franja de estado del terminal]], que se cierra sin cambios. Forma del aviso en [[Barra de calidad de la conexión en la franja de estado]] (variante A, sin corchetes y solo en la franja, por decisión del usuario del 2026-10-28).'
Bloqueada: []
Prueba: 'Con un corte de red corto en el móvil (modo avión) y en escritorio, la barra de la franja pasa a rojo «Sin respuesta» y vuelve sola a verde sin que la pestaña reconecte.'
Esperando a: ''
Fecha de creación: 2026-09-26T16:52:00+02:00
Última modificación: 2026-10-28T14:40:00+01:00
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

El 2026-10-28 el usuario la retoma para cerrar el diseño: las restricciones,
el mecanismo y la forma del aviso de abajo. Sigue `Pendiente` hasta que se empiece a
implementar.

## Restricciones (decididas el 2026-10-28)

Para no contradecir el trabajo de batería de
[[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]]
y [[Alinear los keepalives de las pestañas]]:

- **Solo es un indicador.** No cambia cuándo se da la conexión por caída ni
  cuándo se reconecta. Por eso queda descartado acortar el keepalive de sshj,
  que adelantaría la reconexión.
- **Solo en primer plano.** La detección funciona únicamente con la app en
  primer plano y la pantalla encendida (en escritorio, con la ventana visible
  aunque no tenga el foco), que es cuando el aviso se puede ver. En segundo
  plano no se añade nada y el comportamiento de ADR-0023 no cambia.
- **Sin tráfico de más.** Si ha llegado algo hace poco, eso ya demuestra que la
  conexión responde y no se manda nada. La sonda solo sale cuando la conexión
  lleva unos segundos callada.

## Mecanismo (decidido el 2026-10-28)

La propuesta de partida no sirve: en el protocolo del agente el ACK lo manda la
app al agente (`TypeAck`, cliente → agente), y con la sesión quieta el agente
no envía nada. En ningún nivel se distingue escuchando una conexión quieta de
una muerta, que es justo el caso del modo avión con la pantalla quieta.

Por eso se usa una **sonda SSH**: una petición global con respuesta (como el
`keepalive@openssh.com` con *want-reply*), igual en los niveles 1, 2 y 3 y sin
tocar el agente. Va aparte del `KeepAliveRunner` de sshj, que sigue igual y es
el único que decide cuándo la conexión está caída. Con ProxyJump se mide la
conexión final. La latencia que muestra la barra es lo que tarda la respuesta.

## Forma del aviso (decidida el 2026-10-28)

En [[Barra de calidad de la conexión en la franja de estado]]: `[+]` se
sustituye por una barra de tres fragmentos como el cursor del icono, en las
franjas de estado del terminal, del SFTP y del mouse pad. Las pestañas no
cambian, y `[-]`, `[x]` y el `[!]` de escritorio bloqueado tampoco.

Umbrales iniciales, que se pueden ajustar tras probarlos:

| Barra | Cuándo | Texto |
|---|---|---|
| Tres en verde | respuesta en menos de 500 ms | `Conectado` |
| Dos en naranja | respuesta en 500 ms o más | `Lenta (… ms)` |
| Uno en rojo | 5 s sin respuesta | `Sin respuesta (… s)` |

Para subir a un grado mejor hacen falta dos respuestas buenas seguidas, para
que la barra no parpadee. Bajar es inmediato. Al volver de "sin respuesta" el
tiempo de esa sonda es lo que duró el corte, no la latencia: no se muestra, se
manda otra sonda en el acto y su respuesta decide el grado (se vio en el
emulador como `Lenta (13952 ms)` tras un corte de 12 s, y se corrigió).

Con la conexión callada sale una sonda como mucho cada 3 s
(`QUIET_BEFORE_PROBE`), así que "sin respuesta" aparece entre 5 y 8 s después
del corte.

## Criterios de finalización

- La barra de calidad de
  [[Barra de calidad de la conexión en la franja de estado]] en las franjas del
  terminal, del SFTP y del mouse pad, con los tres grados y los umbrales de
  arriba. Las pestañas siguen como estaban.
- Vuelve a "Conectado" sola al recuperarse, sin reiniciar la sesión ni perder
  la pantalla.
- No cambia cuándo se reconecta, y en segundo plano no sale ninguna sonda.
- Funciona en los niveles 1, 2 y 3.
- Verificado en el Pixel con el modo avión y en escritorio cortando la red y
  añadiendo latencia al servidor de pruebas.

## Verificación

Hecho el 2026-10-28 en la rama del worktree `connection-quality`:

- **Tests unitarios** (`ConnectionQualityTest`, tiempo virtual): sondas solo
  con la conexión callada, ninguna con tráfico, "sin respuesta" a los 5 s,
  recuperación sin mostrar el corte como latencia, lenta al momento y dos
  respuestas buenas para volver. Toda la suite de escritorio pasa.
- **Integración contra sshd real** (`SshjIntegrationTest`): OpenSSH contesta la
  sonda (64 ms en local), la salida de un canal cuenta como recibida y una
  conexión cerrada no contesta.
- **Escritorio, pestaña real contra un sshd desechable**
  (`SessionTabQualityIntegrationTest`, opt-in con `-PtitanQualityContainer`):
  servidor congelado con `docker pause` → `Sin respuesta`; 900 ms de latencia
  con `tc netem` → `Lenta (921 ms)`; las dos vuelven solas a `Conectado`, la
  pestaña no sale de CONNECTED y al dejar de mirar vuelve a bueno sin sondas.
- **Emulador** (build de debug, nivel 3, mismo sshd desechable): verde al
  conectar; `Sin respuesta (12 s)` con el servidor congelado y `Conectado` a los
  ~2 s de descongelarlo; `Lenta (917 ms)` con latencia y vuelta a `Conectado`.
  Las pestañas siguen con `[+]`. Con 40 s congelado no reconecta, igual que una
  build de `main` sin el cambio con la misma prueba.
- **Modo avión en el emulador**: Android cierra los sockets al momento y la
  pestaña pasa directamente a `Reconectando…`, como antes del cambio; no
  ejercita la barra. El caso de la barra (red que se va sin cerrar el socket)
  se probó congelando el servidor.

Pendiente (ver «Cómo probarlo»): la prueba del usuario en el Pixel con el modo avión y en escritorio
cortando la red. Las franjas del SFTP y del mouse pad comparten el componente
y compilan, pero no se han visto en pantalla.

## Cómo probarlo

**Dónde**: la canary construida desde el commit que integra la barra («Show
the connection quality in the status strips») o posterior, en el móvil y en
escritorio. Destino: cualquier sesión SSH de nivel 1, 2 o 3
(`<destino-linux>`).

**Pasos**:

1. Abrir una pestaña contra el destino y dejarla quieta: la franja de estado
   muestra la barra con los tres fragmentos en verde y `Conectado`. Las
   pestañas siguen con `[+]`.
2. En el móvil, activar el modo avión 15–20 s y quitarlo, sin salir de la app.
3. En escritorio, cortar la red (desconectar el wifi o el cable) 15–20 s y
   volver a conectarla.
4. Si se tiene a mano, abrir también una pestaña SFTP y una de mouse pad y
   repetir uno de los cortes: su franja tiene la misma barra.
5. Con la app en segundo plano (o la ventana minimizada) durante el corte, al
   volver la barra debe estar en verde o pasar a rojo en pocos segundos: en
   segundo plano no se sondea.

**Qué debe pasar**: a los 5–8 s del corte, un fragmento rojo y `Sin respuesta
(N s)` contando; al volver la red, verde y `Conectado` en uno o dos segundos,
sin reconectar ni perder la pantalla. Si el sistema cierra la conexión en el
acto (como el modo avión del emulador), la pestaña pasa a `Reconectando…`
como antes, y eso también es correcto: la barra no cambia cuándo se reconecta.
Con una red lenta de verdad, dos fragmentos naranjas y `Lenta (N ms)`.

**Si falla**: una captura de la franja, cuánto duró el corte, el nivel de la
sesión y si la pestaña pasó a `Reconectando…`.

## Resultado

<Se rellena al completar.>
