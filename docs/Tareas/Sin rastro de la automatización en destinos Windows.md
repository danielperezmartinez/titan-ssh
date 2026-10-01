---
Nombre: 'Sin rastro de la automatización en destinos Windows'
Estado: 'Hecha'
Resumen: 'En un destino Windows, la automatización de la sesión sigue dejando rastro en la terminal, y a veces la estropea. Son tres puntos que el usuario vio el 2026-09-30 en el Pixel, por nivel 3. (1) Una pestaña que se engancha a una sesión del agente que ya existía no comprueba la shell del destino, la trata como POSIX y borra filas en una consola ConPTY: quedan trozos del centinela y se pierde el prompt. (2) El último script escribe un centinela aunque detrás no haya nada que esperar; si ese script lanza otra shell (pwsh), el centinela de cmd.exe lo lee PowerShell, sale tal cual y el script caduca a los 30 s. (3) En Windows el centinela no se borra; se probará a vaciar sus filas en su sitio en vez de quitarlas. Hecho el 2026-10-01 y probado en el emulador: el reenganche ya no oculta nada hasta conocer la shell, el último paso va sin centinela, y en Windows las líneas del centinela se vacían sin mover filas (queda un hueco en blanco). Falta que el usuario lo pruebe y acepte ese hueco.'
Decisiones: 'Sale de [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]] al cerrarla (2026-09-30), por petición del usuario. Que la misma sesión guardada en dos pestañas comparta el PTY es lo decidido en [[Transparencia y control del agente en el destino]] y no cambia. Continúa [[Ruta inicial y scripts de inicio en destinos Windows]].'
Bloqueada: []
Fecha de creación: 2026-09-30T21:00:00+02:00
Última modificación: 2026-10-01T10:35:00+02:00
---

# Sin rastro de la automatización en destinos Windows

## Objetivo

Que en un destino Windows (`cmd.exe` o PowerShell) la automatización de la
sesión (la ruta inicial, los scripts de inicio y sus centinelas) no estropee
nunca la terminal y, si se puede, no deje rastro. Lo que vio el usuario está en
**Prueba del usuario** de
[[Terminal fluida con ajuste de líneas y sin rastro de la automatización]].

## Puntos

### 1. Reenganche sin detectar la shell (fallo)

- `SessionTab.concealAutomation` solo se salta los destinos que no son POSIX
  cuando `remoteShell` ya se conoce. `remoteShell` lo rellena `detectShell`,
  y a `detectShell` solo lo llama la automatización cuando la necesita.
- Una pestaña que se engancha a una sesión del agente que ya existía
  (`onAttached(fresh = false)`) no lanza la automatización. `remoteShell` se
  queda en `null`, se borran filas como si fuera POSIX y la consola ConPTY
  se descuadra.
- Pasa con la misma sesión guardada abierta en dos pestañas y al reabrir una
  sesión tras reiniciar la app.
- Arreglo previsto: no borrar nada mientras la shell no se conozca, y
  detectarla también al reengancharse, porque el reenganche reproduce centinelas
  de la ejecución anterior.

### 2. Centinela del último script

- El centinela solo sirve para que el siguiente comando espere al anterior.
  La ruta inicial sin scripts ya no lo escribe (`ScriptRunner`: "alone, the
  sentinel would just print noise").
- Propuesta: tampoco lo escribe el último paso. Resuelve el caso de un último
  script que lanza otra shell (`pwsh`), cuyo centinela de `cmd.exe` imprime
  `…:%errorlevel%:…` literal y acaba en `TIMED_OUT`.
- A valorar: qué hacer si un script que cambia de shell no es el último. Por
  ejemplo, documentarlo en el editor de scripts, o que el paso siguiente espere
  un patrón en lugar del centinela.
- Hay que comprobar qué se pierde: el estado de salida del último script
  quedaría como `SENT`. Mirar quién lee los `ScriptOutcome`.

### 3. Centinela visible en Windows

- Hoy no se borra, a propósito: ConPTY repinta por posición absoluta y quitar
  filas descuadra la pantalla.
- Probar a vaciar las filas del centinela en su sitio, sin moverlas, contra
  el sshd de Windows del PC de desarrollo. Hay que ver qué pasa cuando ConPTY
  repinta: al redimensionar, al hacer scroll y con `cls`.
- Si no se sostiene, se deja visible en Windows y se anota el motivo.

## Criterios de finalización

- Una pestaña que se engancha a una sesión viva de un destino Windows muestra
  la terminal intacta, sin trozos de centinela ni líneas perdidas.
- Un último script que lanza `pwsh` no deja un centinela literal ni caduca.
- Punto 3 resuelto o descartado con el motivo anotado.
- Tests del emulador y de `ScriptRunner` para cada caso. Prueba en el emulador
  contra el sshd de Windows y contra el contenedor de pruebas, que confirma que
  en POSIX no se ha roto nada.

## Lo hecho

### 1. Reenganche

- `SessionTab` no oculta nada mientras no conoce la shell (`concealShell`):
  anota que ha visto un marcador y lo oculta cuando la sonda responde
  (`onShellKnown`).
- Al reengancharse a un PTY vivo (`onAttached(fresh = false)`) lanza la sonda
  de la shell, porque la reproducción puede traer centinelas de la ejecución
  anterior. Si la sonda falla, la shell supuesta (POSIX) también cuenta.

### 2. Último paso sin centinela

- `ScriptRunner` ya no escribe el centinela del último paso de la cadena (el
  `cd` inicial si no hay scripts, o el último script). Su resultado queda como
  `SENT`. Nadie lee los `ScriptOutcome` fuera de los tests
  (`StartScriptAutomation` los descarta), así que no se pierde nada.
- Para un script que abre otra shell y no es el último, el editor de scripts
  avisa debajo de "Esperar a que termine": *"Un script que abre otra shell
  (pwsh, bash…) va el último: la espera de los siguientes usa la sintaxis de la
  shell de antes."* No se ha hecho que el paso siguiente espere un patrón.

### 3. Centinela en Windows: se vacía en su sitio

Se grabó lo que emite ConPTY con el propio `NewPty` del agente, con `cmd.exe`
y `pwsh`, para la misma secuencia de la app: centinelas, redimensionar a más
estrecho y más ancho, scroll, `cls` y PowerShell con PSReadLine. Lo que se vio:

- ConPTY **no ajusta** las líneas largas: las corta con un CR LF explícito en
  el margen (a veces con un BS delante). Por eso, al tratarlo como POSIX,
  quedaban las continuaciones (`TAN_…__`).
- Al redimensionar repinta toda la pantalla desde `ESC[H`, sobre el texto que
  hubiera en cada fila (lo que sobra lo borra después con `EL`), y coloca el
  cursor por posición absoluta (`ESC[17;9H`). Quitar filas lo descuadra;
  vaciarlas en su sitio no.
- PSReadLine repinta la línea de entrada varias veces mientras se teclea, con
  posiciones absolutas.

Con eso, `TerminalEmulator.blankLinesMatching` vacía las líneas de la
automatización sin mover ninguna fila:

- Una fila que llega a la última columna (aunque sea con un espacio) se junta
  con la siguiente.
- Solo se vacían las filas hasta la última coincidencia del patrón, que en
  Windows llega al final de la línea (el token, o `')` en PowerShell). Así no
  se vacía el prompt cuando una salida del centinela ocupa justo el ancho.
- Mientras el cursor está en la línea, espera si aún puede llegar un token (lo
  escrito antes del cursor acaba en un trozo de token, en `'`, o en nada).
- Una fila vaciada que sale por arriba no pasa al scrollback.

Queda **un hueco en blanco** donde estaban el comando del centinela y su salida
(unas 4 o 5 filas por paso esperado). Es el rastro que deja no poder mover
filas en ConPTY.

## Verificación

- Tests: `ScriptRunnerTest` y `WindowsShellAutomationTest` (el último script
  sin centinela y el caso del usuario con `pwsh`),
  `SessionTabAgentAutomationTest` (reenganche a `cmd.exe`: centinelas vaciados
  en su sitio sin mover filas, y reenganche POSIX que los quita al conocer la
  shell) y `TerminalEmulatorTest` (corte duro, token a medio escribir, salida
  que ocupa el ancho y el prompt detrás, PowerShell con `')` y corte en un
  espacio, repintado sobre filas vaciadas, fila vaciada que sale por arriba).
  `:shared:desktopTest` pasa entero (255 tests) y `:androidApp:assembleDebug`
  compila.
- Reproducción de la grabación real de ConPTY, alimentada en trozos de 1, 2, 3,
  5, 7, 13, 31, 64 y 200 bytes y de una vez: en todas las fases, ningún resto
  del centinela y todas las filas y el cursor en la misma posición que sin
  ocultar nada. La grabación y el test no se versionan: tienen rutas y
  carpetas del PC de desarrollo.
- Emulador (2026-10-01), `Pixel_9_Pro_XL` con la build de debug:
  - "Apps (agente)" contra el sshd de Windows del PC, con la ruta inicial y
    dos scripts como la sesión del usuario (`cd <subcarpeta>` y `pwsh -NoLogo`):
    ningún centinela, `pwsh` se abre sin caducar y sin centinela literal.
  - Segunda pestaña de la misma sesión (reenganche): terminal intacta, con el
    prompt de PowerShell y sin trozos del centinela.
  - Abrir el teclado (menos filas), `Get-Location`, girar a horizontal y
    volver, `cls` y `dir`: nada descuadrado ni restos, y la primera pestaña
    igual.
  - "proyecto demo" contra el contenedor de pruebas (POSIX): sin centinelas y
    sin huecos.
  - No se probó en el emulador el reenganche POSIX ("demo (agente)"): otra
    sesión reinstaló la app en el emulador en mitad de la prueba. Lo cubre
    `SessionTabAgentAutomationTest`.
- La sesión "Apps (agente)" del emulador conserva los dos scripts de la
  prueba. Copia previa de la config en `files/titan-config/config.pre-10f.bak`.
- **Prueba del usuario** (2026-10-01): el usuario probó la APK (`v0.1.0-beta.9`)
  en su dispositivo y confirmó que funciona bien y que ya no aparecen los
  centinelas `__TITAN_…__`.

## Resultado

Completada y verificada. La automatización en destinos Windows ya no estropea la
consola ni deja centinelas visibles:
1. Al reengancharse a una sesión viva del agente se detecta la shell antes de
   aplicar cualquier borrado, evitando descuadres en ConPTY.
2. El último paso de una cadena de automatización no genera centinela de fin,
   impidiendo que un script final que invoque `pwsh` desde `cmd.exe` lo consuma
   como entrada y caduque.
3. En Windows las filas del centinela se vacían en su posición original sin
   provocar saltos de línea ni desplazamiento de scrollback.
Validado por el usuario en `v0.1.0-beta.9`.
