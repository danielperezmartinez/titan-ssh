---
Nombre: 'Transparencia y control del agente en el destino'
Estado: 'En curso'
Resumen: 'Hoy el usuario no tiene una forma sencilla de saber si hay un agente de nivel 3 corriendo en un destino, qué sesiones guarda ni de cerrarlo sin reiniciar la máquina: el proceso se llama agent-<versión>-<so>-<arch> (no aparece buscando "titan"), en Windows vive en la sesión 0 (sin escritorio) y en Linux desacoplado con setsid. Objetivo: dar transparencia y control sin romper el principio de que el agente y sus sesiones siguen vivos pase el tiempo que pase. Alcance: quitar el TTL de 30 minutos de las sesiones (ADR-0014), órdenes de consulta y control en el propio agente (estado, cerrar una sesión, detener de forma ordenada), un panel en la app que las usa por SSH desde cualquier plataforma, tres avisos que nunca cierran nada (sesiones en segundo plano, más de 1 GB de memoria, sesión sin conectar 24 horas seguidas) y formas explícitas de cerrar (cerrar la pestaña nunca pierde trabajo; "Terminar en el destino" desde la lanzadera o el panel; eliminar una sesión viva solo permite eliminarla y terminarla, o no eliminarla; si el destino no responde, se termina en la siguiente conexión). Ubicación en la interfaz: lanzadera de Sesiones y panel propio del agente; la pestaña del terminal no cambia. Fuera de alcance: que el agente se cierre solo y el icono en la bandeja de Windows. Plan cerrado con el usuario el 2026-09-29.'
Decisiones: 'El usuario decide el 2026-09-29: el agente no se cierra solo; se quita el TTL de 30 minutos de las sesiones y en su lugar se avisa ([[ADR-0014 Sesiones del agente sin caducidad]], que sustituye el punto GC de [[ADR-0008 Diseño del agente de resiliencia nivel 3]]); el icono en la bandeja de Windows no se hace de momento; los avisos y las formas de cerrar quedan como se describen en la nota; la ubicación en la interfaz está en [[Estado y control del agente en la interfaz]]. Se apoya en el candado y el encuentro por loopback de [[titan-agent instancia única y directorio de estado]] y [[titan-agent punto de encuentro TCP loopback con token]], y en las acciones contextuales de [[Acciones contextuales en las filas de lista]].'
Bloqueada: []
Fecha de creación: 2026-09-29T21:00:00+02:00
Última modificación: 2026-09-30T19:35:00+02:00
---

# Transparencia y control del agente en el destino

## Objetivo

Que el usuario pueda **ver** qué agentes de titan-ssh hay en sus destinos y qué
sesiones guardan, y **cerrarlos** cuando quiera, sin reiniciar la máquina y sin
que el agente deje de cumplir su función: mantener vivo el trabajo pase el
tiempo que pase.

## Decisiones del usuario (2026-09-29)

- El agente **no se cierra solo**. Se descarta terminar el daemon cuando se
  queda sin sesiones: rompe el pilar de no perder trabajo.
- Se **quita el TTL de 30 minutos** de las sesiones y en su lugar **se avisa**
  al usuario. Recogido en [[ADR-0014 Sesiones del agente sin caducidad]].
- Cerrar sesiones o el agente es siempre una **acción explícita del usuario**.
- El **icono en la bandeja de Windows no se hace** de momento.
- Confirmado: un solo agente por usuario y destino, con una sesión del agente
  por sesión guardada. Abrir la misma sesión guardada en dos pestañas muestra
  la misma terminal en ambas; para dos terminales independientes se usan dos
  sesiones guardadas.
- Avisos y formas de cerrar: como se describen en los puntos 4 y 5 del plan.
- Interfaz: lanzadera de Sesiones y panel propio del agente, según
  [[Estado y control del agente en la interfaz]]. Se descarta poner un menú
  del agente o una opción de terminar en la pestaña del terminal.
- Eliminar una sesión viva no ofrece dejarla viva, para que no queden
  sesiones huérfanas.

## Situación de partida (2026-09-29)

- El binario se instala como `agent-<versión>-<so>-<arch>` (`.exe` en Windows):
  buscar "titan" en el Administrador de tareas o con `ps` no lo encuentra.
- En Windows lo lanza sshd, así que corre en la **sesión 0** (sin escritorio).
  En Linux se desacopla con `setsid` desde SSH y no tiene sesión gráfica.
- `--stop` existe, pero mata el proceso sin cerrar los PTY y depende de que
  `agent.json` exista. En el PC de desarrollo había un daemon de una beta
  antigua con `agent.lock` tomado y sin `agent.json`: `--stop` no podía
  pararlo (el usuario lo cerró a mano). Causa probable en el apartado Avance.
- Cada versión se instala con otro nombre, así que un daemon antiguo sigue
  corriendo tras actualizar la app.
- `Registry.GC(sessionTTL)` (`daemon.go`, `sessionTTL = 30 * time.Minute`)
  cierra las sesiones sin cliente con más de 30 minutos sin uso, aunque dentro
  siga corriendo un proceso. Lo quita ADR-0014.
- **Cerrar una pestaña no cierra la sesión del agente**: `AgentTransport` envía
  `BYE` y el agente la conserva. El id de la sesión del agente es el id de la
  sesión guardada (`SessionTab`: `agentSessionId = resolved.session.id`), así
  que volver a abrir esa sesión guardada la reengancha. Sin TTL, toda sesión
  abierta con nivel 3 sigue viva hasta que el usuario la cierre.

## Plan

### 1. Sesiones sin caducidad (ADR-0014)

- El GC solo retira las sesiones cuya shell ya terminó.
- Se mantiene el límite en bytes del buffer de cada sesión.

### 2. Órdenes de consulta y control en el agente

- `--status` (legible y `--json`): versión, PID, hora de arranque, memoria y,
  por sesión, id, shell, creada, último uso, si tiene la app conectada y tamaño
  del buffer.
- `--close-session <id>`: cierra una sesión concreta.
- `--stop` ordenado: cierra los PTY y sale, en vez de matar el proceso.
- Van por el mismo encuentro loopback + token (otra marca de preámbulo), sin
  tocar el protocolo cliente-agente. El token nunca sale del destino.

### 3. Panel del agente en la app

- Pantalla secundaria; se abre desde las acciones de la sesión en la
  lanzadera, desde la franja de memoria y desde las acciones de cada host en
  Configuración → Hosts (detalle en
  [[Estado y control del agente en la interfaz]]).
- Usa `--status --json` por `exec` y ofrece cerrar una sesión o detener el
  agente, con confirmación porque se pierde lo que haya dentro.
- Relaciona cada sesión del agente con su sesión guardada por el id; si la
  sesión guardada ya no existe, la marca como huérfana.
- Si el agente es de una versión anterior a la app, lo indica y ofrece
  actualizarlo avisando de que se cierran sus sesiones. Nunca lo para solo.
- Sirve en Android, Windows y Linux, y con destinos remotos sin escritorio.

### 4. Avisos

Los avisos solo informan y llevan al panel; nunca cierran nada.

- **Sesiones en segundo plano**: línea discreta en la fila de cada sesión de la
  lanzadera que está viva en el destino sin pestaña abierta ("viva en el
  destino · vista hace 3 h").
- **Memoria**: aviso más visible cuando el agente y todo lo que corre dentro de
  sus sesiones supera 1 GB.
- **Sesión abandonada**: aviso cuando una sesión lleva 24 horas seguidas sin
  ningún cliente conectado.

### 5. Formas de cerrar

- **Cerrar la pestaña** conserva la sesión en el destino, como ahora: un
  cierre por accidente no pierde trabajo y reabrir la sesión guardada la
  recupera. La pestaña no ofrece ninguna opción de terminar.
- **"Terminar en el destino"**, en las acciones de la sesión en la lanzadera y
  en el panel, con confirmación en línea.
- **Eliminar una sesión guardada viva** solo ofrece `[x] Eliminar y
  terminarla` o `[<] No`.
- **Si el destino no responde al eliminar** (decidido el 2026-09-29, opción
  A): la sesión se elimina de la app y queda anotada como pendiente de
  terminar; la app la termina en la siguiente conexión a ese host y, mientras
  tanto, el panel la muestra como "pendiente de terminar".

## Notas para la implementación

- Se concreta cómo mide el agente la
  memoria de lo que corre en sus sesiones en cada sistema y cómo sabe la app
  los avisos sin conectarse a cada destino (solo puede consultarlo al
  conectar o al abrir el panel).

## Avance (2026-09-29)

- **Agente (hecho y verificado):** sin TTL (el GC solo retira sesiones cuya
  shell terminó), canal de control con `--status [--json]`,
  `--close-session` y `--stop` ordenado (con el kill autenticado de antes para
  daemons anteriores), memoria por árbol de procesos (paquete `procmem`) y
  republicación de `agent.json` si desaparece. Verificado con `go test` en
  Windows (ConPTY real, procesos reales) y `go test -race` en Linux (Docker).
- **Causa probable de la anomalía del PC de desarrollo:** el consejo de
  `E_AUTH` decía "borra agent.json"; hecho con el daemon vivo, lo dejaba con el
  candado y sin forma de alcanzarlo. El consejo ya no lo pide y el daemon
  republica el fichero.
- **Cliente (hecho):** `AgentControl` y `AgentStatusReport` (catálogo
  [[AgentControl]]), `AgentWatch`, `AgentHostAccess` y `AgentManager`
  (catálogo [[AgentWatch]]), estado persistido en `agents.json`, sincronización
  al atender el agente una pestaña, lanzadera con líneas de estado, franja de
  memoria y acciones, panel del agente y acceso desde Configuración → Hosts
  ([[Estado y control del agente en la interfaz]]). Verificado con tests
  headless, render fuera de pantalla y `AgentControlIntegrationTest` contra
  el sshd de pruebas (estado, cierre pendiente y parada).
- **Emulador Android (hecho, 2026-09-29):** `Pixel_9_Pro_XL` con la build de
  debug contra el sshd de pruebas, con una sesión nueva de nivel 3
  ("demo (agente)", copia previa de la config en
  `files/titan-config/config.pre-agent-panel.bak`): conecta en nivel 3, al
  cerrar la pestaña la lanzadera muestra "viva en el destino", las acciones
  incluyen `[@] Ver el agente del destino`, el panel lista el agente (8,2 MB)
  y la sesión (2,1 MB), "Terminar" la cierra en el destino y lo recordado
  sobrevive a reinstalar la app. Queda una sesión viva en segundo plano para
  que el usuario lo pruebe.
- **Publicada en
  [`v0.1.0-beta.7`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.7)**
  (2026-09-30); comprobación del Release en [[Seguimiento de tareas pendientes]].
- **Falta:** que el usuario instale la APK del Release en el Pixel y confirme
  que funciona.
