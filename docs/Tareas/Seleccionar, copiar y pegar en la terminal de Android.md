---
Nombre: 'Seleccionar, copiar y pegar en la terminal de Android'
Estado: 'En curso'
Resumen: 'En Android no se puede seleccionar texto de la terminal ni mantener pulsado para sacar el menú de copiar y pegar, porque la terminal se pinta celda a celda en un Canvas y no es texto para el sistema. Se añade la selección como capa encima del Canvas (no entra en la caché de filas), con pulsación larga, arrastre, asas y la barra flotante nativa (Copiar, Pegar, Seleccionar todo) mediante LocalTextToolbar, sin perder el rendimiento ganado en Terminal fluida. Implementado y verificado con tests (343) y en el emulador, rendimiento igual que la base; falta la prueba del usuario y llevarlo a main.'
Decisiones: 'Ajuste de [[TerminalView]] y de [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]]. Se descarta pintar las filas como Text dentro de un SelectionContainer (deshace la caché por fila y vuelve a los tirones). La selección vive en líneas absolutas del documento y solo se lee en la fase de dibujo. Escritorio (selección con ratón y Ctrl+Shift+C) queda para después. La barra flotante sigue a la selección mientras llega salida y se oculta cuando sale de la pantalla. Se mantiene LocalClipboardManager (LocalClipboard pide código por plataforma).'
Bloqueada: []
Fecha de creación: 2026-10-13T08:35:00+02:00
Última modificación: 2026-10-13T09:40:00+02:00
---

# Seleccionar, copiar y pegar en la terminal de Android

## Objetivo

Pedido por el usuario: en Android no se puede seleccionar texto de la terminal
para copiarlo, ni mantener pulsado para que salga el menú contextual de Android
con pegar y demás. Hay que añadirlo sin romper las mejoras de rendimiento de
[[Terminal fluida con ajuste de líneas y sin rastro de la automatización]].

## Diseño

- **Estado**: la selección (ancla y extremo, en línea absoluta del documento
  —scrollback más pantalla— y columna) es un estado `@Stable` aparte, como el
  viewport. Solo se lee al dibujar: arrastrarla repinta, no recompone.
- **Gestos** (un único `pointerInput` en lugar del `clickable`):
  - Toque: levanta el teclado como antes y quita la selección si la hay.
  - Pulsación larga: selecciona la palabra bajo el dedo y muestra el menú; en
    vacío, el menú solo ofrece Pegar.
  - Arrastre tras la pulsación larga: amplía la selección y desplaza el
    scrollback al llegar al borde. El arrastre normal sigue siendo scroll.
  - Asas en los extremos para ajustarla.
- **Menú**: `LocalTextToolbar.showMenu`, que en Android es la barra flotante del
  sistema. Pegar usa el mismo camino que el botón de la barra accesoria
  (`TerminalKeys.paste`, con pegado entre corchetes).
- **Pintado**: una pasada después de las filas con rectángulos translúcidos. La
  selección no entra en la caché por fila; sin selección no cuesta nada.
- **Geometría**: el cálculo de qué línea va arriba y dónde se coloca (incluida
  la colocación anticipada mientras sube el teclado) se comparte entre el
  pintado y la conversión de toque a celda.
- **Texto copiado**: une sin salto las filas ajustadas (`wrapped`), quita los
  espacios del final y salta las celdas de continuación de los glifos anchos.
- **Casos borde**: el scrollback recorta por delante al llegar al tope; un
  contador de líneas descartadas en el snapshot corrige la selección.
  Redimensionar, cambiar de pantalla alterna o teclear la quitan.

## Criterios de finalización

- En Android, mantener pulsado sobre la terminal selecciona una palabra y saca
  la barra flotante nativa con Copiar, Pegar y Seleccionar todo.
- La selección se amplía arrastrando y con las asas, también hacia el
  scrollback.
- Lo copiado respeta las líneas ajustadas y no lleva espacios de relleno.
- Pegar desde el menú envía el texto como el botón Pegar.
- El toque sigue levantando el teclado y el arrastre sigue haciendo scroll.
- El rendimiento no empeora: misma medición con `dumpsys gfxinfo` que en
  Terminal fluida (referencia: percentil 95 de 36 ms, percentil 99 de 65 ms).

## Verificación

- `:shared:desktopTest` completo: 343 tests, 0 fallos. `TerminalTextTest`
  (nuevo, 10 tests): texto con saltos y sin relleno, filas ajustadas como una
  sola línea, glifos anchos, celdas invisibles, palabra bajo el dedo (con
  separadores y siguiendo ajustes), posiciones que sobreviven al tope del
  scrollback, cambio de `layoutEpoch` al reescribir líneas y seleccionar todo.
- Compila en Android y escritorio sin avisos nuevos.
- Emulador `Pixel_9_Pro_XL` contra el sshd de pruebas, con la build instalada
  al lado de la de otra sesión (sufijo de paquete temporal, no versionado):
  - Pulsación larga sobre la URL del mensaje de bienvenida: selecciona
    `https://wiki.alpinelinux.org/` sin los `< >` y sale la barra del sistema
    (Copy, Paste, Select all).
  - Copiar y pegar desde una pulsación larga en vacío (menú con Paste y Select
    all): llega a la shell como pegado entre corchetes.
  - Con el teclado abierto, pulsación larga sobre `95` de un `seq 100` y
    arrastre al borde superior: la selección entra sola en el scrollback.
    Arrastrar el asa de inicio la recoloca y el menú vuelve a salir.
  - El portapapeles recibe una línea por número, sin relleno.
  - Seleccionar todo reabre el menú con todo el documento.
  - Un toque quita la selección; el arrastre normal sigue haciendo scroll y el
    toque sigue levantando el teclado.
  - Con salida continua (`while :; do date; …`), la selección se queda sobre
    su texto; la barra la sigue y se oculta cuando sale de la pantalla.
- Rendimiento (`dumpsys gfxinfo`, misma secuencia en una build base de `main`
  y en esta, dos veces cada una: 1200 líneas de scrollback, 6 aperturas y
  cierres del teclado y 30 pulsaciones). La mediana es 18 ms en las dos.

  | Build | p90 | p95 | p99 |
  | --- | --- | --- | --- |
  | Base | 32 / 32 ms | 38 / 36 ms | 61 / 69 ms |
  | Selección | 32 / 30 ms | 34 / 34 ms | 73 / 48 ms |

  Sin diferencias más allá del ruido del emulador, que renderiza por software.
- Pendiente: que el usuario lo pruebe y la integración en `main`.

## Resultado

- `TerminalModel` / `TerminalEmulator`: el snapshot lleva `droppedLines` y
  `layoutEpoch`.
- `TerminalText` (nuevo, ver [[TerminalText]]): `DocPos`, palabra bajo una
  posición, texto entre dos posiciones y seleccionar todo.
- `TerminalCanvas`: la geometría de las filas sale a `terminalGeometry`, que
  comparten el pintado y el toque. La selección se pinta después de las filas
  y el cursor, como capa. Al seguir la salida con el scroll hacia atrás se
  cuentan también las líneas descartadas, así que funciona con el scrollback
  lleno.
- `TerminalSelection.kt` (nuevo): el estado de la selección, su pintado con
  asas y el gesto (`terminalTouch`). El gesto distingue toque, pulsación larga
  y arrastre de selección, toma los eventos en la pasada inicial para que el
  `scrollable` no desplace mientras se selecciona, y desplaza en los bordes.
- `TerminalView`: en Android el `clickable` del panel pasa a `terminalTouch`.
  La barra flotante sale con `LocalTextToolbar` y Pegar comparte el camino del
  botón de la barra accesoria. Escribir, un reajuste o un cambio de pantalla
  quitan la selección. En escritorio no cambia nada.
- No se migró a `LocalClipboard`: crear un `ClipEntry` desde código común pide
  código por plataforma; se sigue con `LocalClipboardManager`.
