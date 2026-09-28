---
Nombre: 'Terminal fluida con ajuste de líneas y sin rastro de la automatización'
Estado: 'En curso'
Resumen: 'Tres fallos que el usuario vio en el emulador: el centinela interno __TITAN_…__ de los scripts de inicio salía en la terminal, las líneas largas se perdían por la derecha y la terminal iba a tirones al abrir el teclado y al escribir. Causa principal del segundo: en Android la fuente JetBrains Mono nunca se cargaba (el módulo compartido no empaquetaba los recursos de Compose como assets) y toda la app pintaba en Roboto proporcional. Arreglado activando androidResources y rehaciendo la terminal: el emulador marca las líneas ajustadas y las reajusta al redimensionar, admite glifos anchos y más modos; la vista pinta celda a celda sobre un Canvas con caché por fila, redimensiona el PTY cuando el tamaño se estabiliza y permite scroll; SessionTab borra de la terminal las líneas del centinela y de las sondas de tmux. Verificado con tests (220, integración incluida) y en el emulador; falta que el usuario lo confirme.'
Decisiones: 'Ajuste de [[Terminal multipestaña con sesiones simultáneas]] y de [[Scripts de inicio por sesión]]. El centinela sigue tecleándose en la shell (solo ella sabe cuándo acaba un comando), pero la pestaña borra sus líneas; en Windows no, porque ConPTY repinta por posición absoluta y se descuadraría, y dentro de tmux/screen tampoco. Superficies en [[TerminalEmulator]], [[TerminalView]], [[TerminalKeys]] y [[ScriptRunner]].'
Bloqueada: []
Fecha de creación: 2026-09-28T10:45:00+02:00
Última modificación: 2026-09-28T11:10:00+02:00
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
- **Pendiente**: que el usuario lo pruebe en el emulador y confirme.

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
