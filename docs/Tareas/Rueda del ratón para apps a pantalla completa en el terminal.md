---
Nombre: 'Rueda del ratón para apps a pantalla completa en el terminal'
Estado: Hecha
Resumen: 'Con Claude Code en modo `tui: fullscreen` (pantalla alternativa + ratón activado) deslizar el dedo movía la scrollback local de la pantalla principal (p. ej. el saludo de fastfetch) en vez del historial de la app, y la respuesta de Claude parecía perderse. El emulador pasa a registrar los modos de ratón y el deslizamiento se envía a la app como rueda (SGR/X10) o, sin ratón, como flechas (modo 1007); en pantalla alternativa no se muestra la scrollback principal.'
Decisiones: 'Ajuste de [[Terminal multipestaña con sesiones simultáneas]].'
Bloqueada: []
Versión: '1.1.0'
Fecha de creación: 2026-10-20T09:00:00+02:00
Última modificación: 2026-10-20T12:00:00+02:00
---

# Rueda del ratón para apps a pantalla completa en el terminal

## Objetivo

El usuario abre Claude Code por SSH desde el Pixel en un destino Linux cuya
shell (fish) saluda con fastfetch. Con la interfaz a pantalla completa de Claude
Code, al deslizar para leer la respuesta siempre aparecía el saludo de fastfetch
y el texto de Claude "se perdía".

Diagnóstico (grabando los bytes de la sesión en un PTY):

- fastfetch solo emite color y texto; no rompe el emulador.
- Claude Code en `tui: fullscreen` entra en la pantalla alternativa
  (`?1049h`), activa el seguimiento del ratón (`?1000h ?1002h ?1003h ?1006h`) y
  guarda su historial dentro de su propia vista: espera eventos de rueda para
  desplazarlo.
- El emulador ignoraba los modos de ratón y la vista dejaba desplazar la
  scrollback de la pantalla principal estando en la alternativa, así que el
  gesto enseñaba lo que había antes de abrir Claude.

## Criterios de finalización

- El emulador registra los modos de ratón (1000/1002/1003), la codificación SGR
  (1006) y el modo de desplazamiento alternativo (1007), y los expone en el
  snapshot; se reinician al hacer RIS.
- Con el ratón activado, deslizar envía eventos de rueda a la app en la
  codificación que pidió, sin mover la scrollback local.
- En pantalla alternativa sin ratón, deslizar envía flechas arriba/abajo
  (como xterm con 1007 activo por defecto) y no enseña la scrollback principal.
- En la pantalla principal sin ratón, el desplazamiento local sigue igual.
- Tests del emulador y de la codificación.
- Probado en el emulador Android con Claude Code a pantalla completa.

## Verificación

- `TerminalWheelTest` (7 casos: modos del emulador, RIS, SGR, X10, flechas con
  y sin DECCKM, 1007 desactivado, saludo largo en la scrollback principal) y la
  suite `:shared:desktopTest` completa, en verde.
- Emulador Android (`Pixel_9_Pro_XL`, build de debug) contra el destino de
  pruebas, después de llenar la scrollback con `seq 150` y abrir un programa
  que entra en la pantalla alternativa con `?1000h ?1002h ?1006h` e imprime lo
  que recibe:
  - deslizar hacia abajo llega como `ESC[<64;1;13M` (una por línea
    arrastrada) y hacia arriba como `ESC[<65;1;13M`;
  - la scrollback principal (`seq`) ya no aparece encima de la app;
  - al salir de la app, deslizar vuelve a recorrer la scrollback local.
- El usuario lo confirmó con Claude Code en `tui: fullscreen` desde el Pixel
  (1.1.1): el desplazamiento funciona bien.

## Resultado

- `TerminalEmulator` registra los modos 1000/1002/1003, 1006 y 1007 (este
  activo por defecto) y los expone en `TerminalSnapshot` (`mouseTracking`,
  `sgrMouse`, `alternateScroll`); RIS los reinicia.
- `TerminalWheel` (nuevo, `commonMain`) decide a dónde va un desplazamiento y
  lo codifica: rueda SGR o X10 si la app pidió el ratón, flechas en la
  pantalla alternativa sin ratón, `null` para el scroll local.
- `TerminalView` pasa el `scrollable` por `TerminalWheel`: una muesca de rueda
  por cada altura de línea arrastrada, en la fila central de la pantalla. Vale
  también para la rueda del ratón en escritorio.
- `terminalGeometry` no deja desplazar la scrollback de la pantalla principal
  mientras se está en la alternativa.

## Rendimiento (tras publicar la 1.1.0)

El usuario notó pequeños trompicones al desplazar Claude desde el Pixel.

- Claude Code responde a cada evento de rueda en ~3 ms con ~210 bytes (repinta
  por diferencias todas las filas visibles): no es el cuello de botella.
- El pintor del terminal cacheaba la maquetación de cada fila por instancia;
  al desplazar, la app reescribe todas las filas y se maquetaban todas otra
  vez en cada evento. Se añade una caché por contenido (las filas que solo se
  han movido una línea reutilizan su maquetación).
- Medido en el emulador con `dumpsys gfxinfo`, 12 arrastres sobre un programa
  que imita a Claude (repinta toda la pantalla por evento), dos pasadas por
  build: percentil 90 de 48/48 ms → 36/36 ms; "Slow issue draw commands"
  57/49 → 25/17. El emulador de este PC ya da 32 ms con el scroll local, así
  que el resto es su propio límite.
- Lo que queda depende de la red: cada paso espera la ida y vuelta al destino,
  y el desplazamiento va por líneas enteras en vez de por píxeles.
- La caché por contenido se publicó en la **1.1.1**; la rueda llegó en la 1.1.0.
