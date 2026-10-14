---
Nombre: 'Conexiones en segundo plano según lo que pueda perder cada pestaña'
Número: 23
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-14. En Android, con la pantalla bloqueada o la app en segundo plano, el sistema corta la red a la app sin congelarla: hoy las sesiones se caen y la app reintenta 15 min en vano. Se decide por pestaña según lo que pueda perder: si alguna lo necesita (nivel 1, todavía conectando, túneles activos o una descarga SFTP en curso), un servicio en primer plano mantiene conectadas todas las pestañas; si ninguna lo necesita (nivel 2 o 3, mouse pad, SFTP parado), tras 60 s en segundo plano la app pausa sus conexiones (el agente y sus PTY siguen vivos en el destino) y reconecta al volver. Además, nunca se reintenta con la red bloqueada.'
Decisión: 'Un servicio en primer plano mantiene todas las conexiones mientras alguna pestaña pueda perder trabajo al cortarse; si ninguna puede, la app pausa sus conexiones tras 60 s en segundo plano y reconecta al volver; y nunca reintenta mientras Android le bloquee la red.'
Consecuencias: 'El nivel 1 sobrevive por fin al bloqueo de pantalla, a cambio de una notificación permanente y del consumo de sus keepalives. Con solo sesiones de nivel 2 o 3 no se gasta nada en segundo plano y la vuelta tarda uno o dos segundos. Hay que verificar en un móvil real el tipo de servicio, si los keepalives aguantan con la CPU dormida y el consumo; alinear los keepalives entre pestañas queda en [[Alinear los keepalives de las pestañas]].'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-14T20:00:00+02:00
Última modificación: 2026-10-14T20:00:00+02:00
---

# ADR-0023 · Conexiones en segundo plano según lo que pueda perder cada pestaña

## Contexto

El usuario notó que el móvil gasta más batería cuando deja varias sesiones
abiertas y lo bloquea. La medición en el emulador
([[Medir el consumo de batería en segundo plano]]) encontró la causa:

- En Android 15, con la pantalla bloqueada y la app delante (`TOP_SLEEPING`)
  o con la app en segundo plano, el sistema **bloquea la red** a la app
  (`DOZE|APP_BACKGROUND`) pero **no congela el proceso**: el congelador solo
  actúa sobre procesos *cached*.
- Las sesiones se caen al momento y la app entra en el bucle de
  [[Reconexión que no se rinde tras un corte largo]] contra una red que no la
  deja salir: con dos pestañas, unos 78 intentos y casi 4 s de CPU en 15 min.
  Cada cambio de red (Wi-Fi ↔ datos) relanza otro ciclo de 15 min en todas las
  pestañas.

Es decir, la app gasta batería y, aun así, **no mantiene las sesiones**. El
nivel 1 de [[ADR-0003 Modelo de resiliencia por niveles]] pierde el shell cada
vez que se bloquea el móvil, justo lo que el producto promete evitar.

La promesa de titan-ssh es **no perder el trabajo**, no tener un socket
abierto. Qué hace falta para cumplirla depende del nivel efectivo de cada
pestaña:

- **Nivel 1**: el shell, sus procesos y la línea a medio escribir viven solo
  mientras dura la conexión. Solo se conservan si la conexión no se cae.
- **Niveles 2 y 3**: tmux/screen o el agente conservan el PTY en el destino
  ([[ADR-0008 Diseño del agente de resiliencia nivel 3]],
  [[ADR-0014 Sesiones del agente sin caducidad]]). La app no guarda localmente
  lo que se teclea: cada tecla sale al momento hacia el PTY remoto, así que
  una línea sin enviar (sin Enter) está en el editor de línea del shell remoto
  y sigue ahí al volver. La salida producida mientras tanto se recupera con el
  replay por offset del agente. Cortar la conexión del cliente no pierde nada.

## Decisión

### 1. Lo que cada pestaña puede perder

Cada pestaña abierta declara si **necesita seguir conectada**. Cuenta su
estado efectivo, no lo configurado: una sesión configurada con agente que
acabó en nivel 1 (agente no disponible) cuenta como nivel 1.

| La pestaña… | ¿Necesita seguir conectada? |
| --- | --- |
| Nivel 1, o todavía conectando (aún no se sabe su nivel) | Sí |
| Tiene túneles activos (otras apps pueden estar usándolos) | Sí |
| Tiene una descarga SFTP en curso | Sí (se reanudaría, pero mejor que acabe) |
| Nivel 2 o 3 sin túneles, mouse pad, explorador SFTP parado | No |

### 2. Si alguna pestaña lo necesita: servicio en primer plano

- Mientras al menos una pestaña necesite seguir conectada, la app mantiene un
  **servicio en primer plano** con su notificación (p. ej. "titan-ssh · 3
  sesiones conectadas"). Con él, Android no bloquea la red de la app.
- Con el servicio activo, **todas** las pestañas siguen conectadas, también las
  de nivel 2 y 3: con la app despierta y con red, una conexión más cuesta poco,
  y al volver todo está al instante.
- El servicio se arranca **con la app en primer plano**, en cuanto aparece una
  pestaña que lo necesita (Android 12+ restringe arrancarlo desde segundo
  plano), y se para en cuanto ninguna lo necesita.
- Tipo de servicio propuesto: `specialUse`, sin límite de tiempo. `dataSync`
  tiene un tope de 6 h al día desde Android 15. Se confirma en la
  implementación.
- Sin permiso de notificaciones (Android 13+), el servicio funciona igual;
  la app lo pide la primera vez que hace falta y explica para qué.

### 3. Si ninguna lo necesita: pausa en segundo plano

- Tras **60 s** en segundo plano (pantalla bloqueada u otra app delante), la
  app **pausa** las conexiones: cierra limpiamente el transporte de cada
  pestaña, sin keepalives ni reintentos. El margen evita cortar por cambiar un
  momento de app o mirar una notificación.
- La pausa solo afecta a la conexión del cliente: el agente, sus sesiones y
  sus PTY siguen vivos en el destino y nunca se cierran por inactividad
  ([[ADR-0014 Sesiones del agente sin caducidad]]). En el nivel 2, tmux/screen
  queda desenganchado.
- Al volver al frente, las pestañas pausadas reconectan de inmediato y
  recuperan la pantalla exacta. La franja de estado lo muestra como pausa,
  no como corte.

### 4. Nunca reintentar con la red bloqueada

En cualquier caso, mientras Android tenga bloqueada la red de la app
(`NetworkCallback.onBlockedStatusChanged`), las pestañas no reintentan ni
cuentan ese tiempo en el plazo de 15 min. El aviso de vuelta de la red no
relanza nada mientras siga bloqueada. Cuando se desbloquea, reconectan como
ahora.

### 5. Escritorio

En escritorio el sistema no bloquea la red a la app en segundo plano: nada
cambia. La regla del punto 1 vive en código común; el servicio y la pausa son
de Android.

## Alternativas consideradas

- **Pausar siempre en segundo plano** — barato, pero el nivel 1 perdería el
  shell cada vez que se bloquea el móvil: contradice el pilar del producto.
- **Servicio en primer plano siempre que haya sesiones** — mantiene todo, pero
  gasta batería y muestra una notificación permanente también cuando solo hay
  sesiones de nivel 3, que no pierden nada al pausar.
- **Con el servicio activo, pausar igualmente las de nivel 2 y 3** — algo menos
  de consumo, pero tardan uno o dos segundos en volver. El usuario prefiere
  que, si hay servicio, todo siga conectado.
- **Dejarlo como está y solo dejar de reintentar con la red bloqueada** — quita
  el gasto inútil, pero no evita que las sesiones de nivel 1 se pierdan.

## Consecuencias

- Positivas:
  - El nivel 1 sobrevive al bloqueo de pantalla.
  - Con solo sesiones de nivel 2 o 3, la app no gasta nada en segundo plano y
    no muestra notificación.
  - Desaparece el bucle de reintentos con la red bloqueada.
- Negativas / compromisos:
  - Notificación permanente mientras haya una pestaña que lo necesite.
  - Con el servicio activo, el consumo es el de los keepalives (cada 30 s por
    conexión, sin alinear). Alinearlos queda en
    [[Alinear los keepalives de las pestañas]].
  - Volver a una app pausada tarda uno o dos segundos en reconectar.
- A verificar en un móvil real:
  - Si los keepalives salen a tiempo con la CPU dormida. Los temporizadores de
    corrutinas no despiertan la CPU; si la conexión no aguanta la noche
    (una VPN, el NAT), hará falta otra solución, por ejemplo una alarma.
  - El tipo de servicio y su comportamiento en Android 15/16.
  - El consumo real con el servicio activo frente a la pausa.

Implementación en [[Conexiones en segundo plano según lo que pueda perder cada pestaña]],
que concreta "todavía conectando" como una pestaña ya conectada cuyo nivel aún
no se conoce y que no espera al agente: una que nunca ha llegado a conectar no
tiene nada que perder.
