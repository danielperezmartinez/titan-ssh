---
Nombre: 'Terminal fluida con ajuste de líneas y sin rastro de la automatización'
Estado: 'Hecha'
Resumen: 'Tres fallos que el usuario vio en el emulador: el centinela interno __TITAN_…__ de los scripts de inicio salía en la terminal, las líneas largas se perdían por la derecha y la terminal iba a tirones al abrir el teclado y al escribir. Causa principal del segundo: en Android la fuente JetBrains Mono nunca se cargaba (el módulo compartido no empaquetaba los recursos de Compose como assets) y toda la app pintaba en Roboto proporcional. Arreglado activando androidResources y rehaciendo la terminal: el emulador marca las líneas ajustadas y las reajusta al redimensionar, admite glifos anchos y más modos; la vista pinta celda a celda sobre un Canvas con caché por fila, redimensiona el PTY cuando el tamaño se estabiliza y permite scroll; SessionTab borra de la terminal las líneas del centinela y de las sondas de tmux. Verificado con tests (220, integración incluida) y en el emulador. El 2026-09-30 el usuario lo probó en el Pixel con la beta.7 contra un destino Windows por nivel 3: confirma la fluidez y el ajuste de líneas. El centinela sigue viéndose en Windows, que quedaba fuera del alcance, y salió un fallo nuevo al reengancharse (ver Prueba del usuario). Cerrada así por decisión del usuario; lo de Windows sigue en Sin rastro de la automatización en destinos Windows.'
Decisiones: 'Ajuste de [[Terminal multipestaña con sesiones simultáneas]] y de [[Scripts de inicio por sesión]]. El centinela sigue tecleándose en la shell (solo ella sabe cuándo acaba un comando), pero la pestaña borra sus líneas; en Windows no, porque ConPTY repinta por posición absoluta y se descuadraría, y dentro de tmux/screen tampoco. Superficies en [[TerminalEmulator]], [[TerminalView]], [[TerminalKeys]] y [[ScriptRunner]].'
Bloqueada: []
Fecha de creación: 2026-09-28T10:45:00+02:00
Última modificación: 2026-09-30T21:00:00+02:00
---

# Terminal fluida con ajuste de líneas y sin rastro de la automatización

## Objetivo

Encontrado por el usuario probando en el emulador:

1. Al iniciar se escribe un comando con `__TITAN…`. Es el centinela interno con
   el que `ScriptRunner` sabe que un script ha terminado; el usuario no debería
   verlo.
2. Con líneas muy largas se pierde el contenido por la derecha: no salta de
   línea.
3. El rendimiento es malo: al abrir el teclado la pantalla se encoge a tirones,
   y al escribir también.

## Criterios de finalización

- Ni el centinela de los scripts ni las sondas del multiplexor dejan rastro en
  la terminal de un destino POSIX.
- Las líneas largas saltan de línea en el ancho visible y nada se corta por la
  derecha; al cambiar el ancho, el contenido se reajusta.
- Abrir y cerrar el teclado y escribir van fluidos en el emulador.

## Causas

1. **Centinela visible.** `ScriptRunner` teclea tras cada script esperado
   (`waitForCompletion`, activado por defecto) un `printf` con un token
   `__TITAN_<hex>__` y espera a leerlo con el código de salida. La shell lo
   repite en pantalla y luego imprime el resultado: dos líneas de ruido. Lo mismo
   las sondas `TITANMUX_<hex>` del nivel 2.
2. **Líneas cortadas por la derecha.** El APK de Android **no llevaba la fuente**:
   el plugin `com.android.kotlin.multiplatform.library` no empaqueta los
   recursos de Compose como assets si `androidResources` está desactivado (lo
   está por defecto), y `Font(Res.font…)` caía sin avisar a Roboto. Toda la app
   pintaba en proporcional (se ve en la captura previa). La rejilla se medía con
   la anchura media de las filas pintadas, pero una fila con letras anchas
   ocupaba más y se recortaba. Además, cada fila era un `Text` con un estilo por
   celda, así que cualquier glifo de otra fuente desplazaba el resto de la fila,
   y al estrechar el terminal el emulador recortaba las filas sin reajustarlas.
3. **Tirones.** Cada trozo de salida recomponía la vista entera y volvía a
   maquetar todas las filas visibles (un `SpanStyle` por celda); al abrir el
   teclado, `imePadding` cambia el alto en cada frame y cada uno disparaba un
   `resize` del emulador y del PTY (el remoto redibujaba otra vez), además de
   recomponer toda la vista por leer `WindowInsets.ime` en composición. Al
   encoger, el emulador perdía las filas de abajo (la del cursor incluida) y al
   crecer quedaba media pantalla vacía.

## Verificación

- `:shared:desktopTest` completo con los tests de integración contra el sshd de
  pruebas (`tools/test-sshd/`, clave desechable): 220 tests, 0 fallos. Nuevos
  tests del emulador: reajuste al estrechar y al ensanchar, filas al scrollback
  y de vuelta al cambiar el alto, glifos anchos y fuera del BMP, autowrap
  diferido, modos, respuestas DSR/DA y borrado de líneas. El test de integración
  de scripts comprueba ahora que `__TITAN_` no queda en la terminal.
- APK con la fuente en `assets/composeResources/…`; la app entera pinta en
  JetBrains Mono.
- Emulador `Pixel_9_Pro_XL` contra el contenedor de pruebas: al conectar se ven
  el `cd` y `echo sesión lista` sin centinela; una orden de 70 caracteres y su
  salida saltan de línea justo en el borde; el contenido sube pegado al teclado
  y al cerrarlo la pantalla se vuelve a llenar; el scroll hacia atrás funciona.
- Rendimiento (`dumpsys gfxinfo`, misma secuencia en las dos versiones: 1200
  líneas de scrollback, 6 aperturas y cierres del teclado y 30 pulsaciones):
  percentil 95 de 150 ms a 36 ms, percentil 99 de 350 ms a 65 ms. La mediana
  (18 ms) es la del emulador, que renderiza por software.
- El usuario lo probó el 2026-09-30 en el Pixel (ver **Prueba del usuario**)
  y confirma la fluidez y el ajuste de líneas. Lo probó contra un destino
  Windows, así que el borrado del centinela en un destino POSIX solo está
  verificado por el test de integración y en el emulador. El usuario decide
  cerrar la tarea así.

## Prueba del usuario (2026-09-30)

Prueba en el Pixel con `v0.1.0-beta.7`, contra un destino Windows por nivel 3.
La sesión guardada hace `cd` a una ruta inicial y lanza dos scripts (otro `cd`
y `pwsh`), y se abrió en dos pestañas.

- **Confirmado**: la terminal va fluida y las líneas largas ya no se cortan.
- **Centinela visible en Windows**: es lo que se implementó (en Windows no se
  borra, ver *Decisiones*), pero el usuario no quiere verlo.
- **Fallo nuevo: restos del centinela al reengancharse**. La segunda pestaña
  no abre otra terminal. Se engancha a la misma sesión del agente que la
  primera, como se decidió en
  [[Transparencia y control del agente en el destino]]: las dos pestañas
  muestran los mismos tokens.
  - Como el PTY no es nuevo, no arranca la automatización y nunca se llama a
    `detectShell`. `remoteShell` se queda en `null`, `concealAutomation` lo
    trata como POSIX y borra filas en una consola ConPTY.
  - Solo quita la fila física que contiene el token entero. Quedan las
    continuaciones de las líneas ajustadas (`TAN_…__`, `…483a__`) y se pierde
    la línea del prompt de PowerShell que llevaba el eco.
  - Pasa en cualquier reenganche a un destino Windows desde una pestaña que no
    ha lanzado la automatización: una segunda pestaña, o volver a abrir la
    sesión tras reiniciar la app.
- **Centinela de `cmd.exe` escrito en PowerShell**. El último script lanza
  `pwsh`, y quien lee su centinela (`echo …:%errorlevel%:…`) ya es
  PowerShell. Lo imprime tal cual, nunca casa con `sentinelPattern` y el
  script acaba en `TIMED_OUT` a los 30 s. Si hubiera scripts detrás, se
  abortarían.

Estos tres puntos siguen en
[[Sin rastro de la automatización en destinos Windows]].

## Resultado

- `shared/build.gradle.kts`: `androidResources { enable = true }`.
- `TerminalModel`: `CellStyle` compartido por celdas, `TerminalCell` con punto de
  código y anchura, `TerminalRow` inmutable con `wrapped`, y el snapshot con
  visibilidad del cursor, pantalla alterna, filas en uso y modos.
- `TerminalEmulator`: filas con caché inmutable, autowrap diferido, glifos
  anchos, reajuste al redimensionar, más SGR y modos (DECTCEM, DECCKM, DECAWM,
  pegado entre corchetes), `REP`, `CNL/CPL`, `CSI s/u`, DSR/DA y
  `eraseLinesMatching`.
- `TerminalCanvas` (nuevo) y `TerminalView`: pintado celda a celda en un Canvas
  con caché por fila, medida de celda tras precargar la fuente, resize con
  150 ms de espera y colocación anticipada de las filas, scroll, teclado sin
  recomponer por frame, flechas en modo aplicación y pegado entre corchetes.
- `SessionTab`: borra las líneas de la automatización en destinos POSIX y
  devuelve al remoto las respuestas del emulador.

Hallazgo aparte, sin tocar: `adb shell input text` en ráfaga pierde caracteres
en el campo oculto que recoge el teclado (con toques reales del teclado no pasa).
Con un teclado físico muy rápido podría pasar lo mismo; queda anotado.
