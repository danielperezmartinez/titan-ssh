---
Nombre: 'Pantalla completa y escala del escritorio remoto'
Estado: 'Por probar'
Estado desde: 2026-11-18
Resumen: 'Dar al visor del escritorio remoto pantalla completa y modos de escala (ajustar a la ventana o tamaño real 1:1 con minimapa, más zoom siguiendo al cursor), porque en un portátil de pantalla pequeña el destino se ve tan reducido que cuesta leer. En escritorio los controles van en la franja de estado y en una barra flotante en pantalla completa; en el móvil, tras [#] vista, con pellizco para el zoom y modo inmersivo.'
Decisiones: 'Diseño en [[Controles del visor del escritorio remoto con barra movible]] (tras la primera prueba en la canary.50: minimapa que se oculta y una sola barra flotante, movible y opcionalmente oculta) y regla de la franja en [[Franja de estado según la plataforma]] (2026-11-18). Elegir el monitor queda fuera: [[Elegir el monitor en el escritorio remoto]]. La calidad de imagen, si hace falta, en [[Calidad de imagen del escritorio remoto]]. El indicador de red de la franja es el componente que ya existe (ConnectionQualityBar); no se rehace.'
Bloqueada: []
Prueba: 'Pantalla completa, 1:1 con desplazamiento por los bordes y zoom del escritorio remoto en la canary de escritorio y de Android'
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-18T20:25:00+01:00
Última modificación: 2026-11-18T22:30:00+01:00
---

# Pantalla completa y escala del escritorio remoto

## Objetivo

Que el escritorio remoto se pueda leer y usar cómodamente desde una pantalla
pequeña (un portátil, un móvil): pantalla completa, tamaño real 1:1 y zoom,
según [[Controles del visor del escritorio remoto con barra movible]].

## Contexto

- El visor es `MousepadView` con `tab.remoteScreen` (`ui/MousepadView.kt`), y
  el encaje de la imagen es `ScreenViewport` (`terminal/RemoteScreen.kt`):
  escala para que quepa por un zoom entero, centrada en el cursor.
- Hoy el zoom (1x–3x) solo existe en Android, con el chip `zoom Nx` de la
  franja.

## Criterios de finalización

- Escritorio: en la franja `ajustar | 1:1`, `− zoom +`, `Ctrl+Alt+Supr` y
  `[ ] completa`, junto a la barra de calidad existente.
- 1:1 enseña el destino a su tamaño real, sigue al cursor y muestra un
  minimapa.
- Pantalla completa en escritorio: ventana a pantalla completa sin cabecera,
  pestañas ni franja, con barra flotante al acercar el ratón arriba y un aviso
  de cómo salir. Se sale con el botón o `Ctrl+Alt+Intro`.
- Atajos `Ctrl+Alt+Intro` (pantalla completa) y `Ctrl+Alt` + rueda (zoom),
  sin que lleguen al destino. Los de `Ctrl+Alt+0/1/+/-` se descartan al
  implementar: chocan con AltGr en Windows (ver la decisión).
- Móvil: `[#] vista` en la franja abre el panel (zoom 1x–4x, ajustar | 1:1,
  `Ctrl+Alt+Supr`, pantalla completa); pellizco para el zoom; pantalla
  completa inmersiva con `[≡]`.
- La escala y el zoom se recuerdan por sesión.
- Tests de la lógica de escala y desplazamiento; probado en escritorio y en el
  emulador.

## Verificación

- Tests de escritorio en verde, con tests nuevos de `ScreenViewport`
  (tamaño real, recorte y desplazamiento por los bordes).
- Compilan la app de escritorio y la de Android.
- Visor dibujado fuera de la app (ImageComposeScene) con una pantalla
  simulada de 1920×1080: franja con los controles (ajustar al 67 %), 1:1 al
  100 % con minimapa, pantalla completa con la pestañita y el aviso, y el
  panel `[#] vista` del móvil.
- Sin probar en el emulador: la memoria libre del equipo no daba para el
  emulador y la build a la vez. Lo prueba el usuario en la canary.
- Primera prueba del usuario (canary.50, 2026-11-18): pide ocultar el
  minimapa, mover la barra de la pantalla completa y que la pestañita y la
  barra desplegada sean una sola cosa. Hecho en la siguiente canary, dibujado
  fuera de la app (barra plegada movida al borde derecho, `[x] minimapa` en
  la franja y en el panel).

## Cómo probarlo

**Dónde:** la canary de escritorio y la de Android desde el commit
la canary que lleve el commit «Make the full screen bar one movable element» (posterior a `1.5.1-canary.50`), contra un destino con escritorio remoto
(`<destino-escritorio>`).

**Escritorio (portátil):**

1. Abrir la sesión de escritorio remoto. En la franja, a la derecha del
   indicador de red, deben salir `ajustar | 1:1`, `− NN% +`,
   `Ctrl+Alt+Supr` y `[ ] completa`.
2. Pulsar `1:1`: el destino se ve a su tamaño real, con un minimapa abajo a
   la derecha. Su `[x]` lo oculta y `[ ] minimapa` en la franja lo vuelve a
   enseñar. Llevar el ratón a un borde y dejarlo quieto: la vista se
   desplaza hacia ese lado y se para en el borde de la pantalla del destino.
   Hacer clic en algo tras desplazarse: el clic cae donde está el ratón.
3. `+` y `−` cambian el zoom; `Ctrl+Alt` + rueda también.
4. `[ ] completa` (o `Ctrl+Alt+Intro`): la ventana ocupa toda la pantalla,
   sin cabecera, pestañas ni franja, con un aviso abajo unos segundos.
   Arriba queda la barra plegada (asa `⋮⋮`, red, nombre, `▸`).
   - Pasar el ratón por encima: la misma barra crece con los controles y se
     pliega al irse el ratón; `[ ] fijar` la deja abierta.
   - Arrastrarla por `⋮⋮` a otro sitio (a un lado, abajo): se queda ahí y,
     al desplegarse, crece hacia donde hay sitio.
   - `[ ] ocultar`: plegada desaparece; llevar el ratón al centro del borde
     más cercano la vuelve a desplegar.
   - Salir con `[x] salir de completa` o `Ctrl+Alt+Intro`: la ventana vuelve
     a su tamaño.
5. Cerrar la pestaña y volver a abrir la sesión: conserva la escala, el
   zoom, el minimapa y dónde estaba la barra (y si se oculta).

**Android:**

1. Abrir la sesión: en la franja sale `[#] vista · 1x` (ya no `zoom 1x`).
   Al tocarlo se abre el panel con zoom, escala, `Ctrl+Alt+Supr` y
   pantalla completa.
2. Pellizcar sobre la imagen: hace zoom (1x a 4x) siguiendo al cursor. Dos
   dedos sin separarlos siguen haciendo scroll.
3. Pantalla completa (desde el panel o con `[^]` de las pestañas):
   desaparecen las barras del sistema y de la app; `[≡]` abre el panel y
   `[⌨]` enseña los botones del ratón y la barra de teclas.

**Si falla:** versión de la canary, captura y qué paso falló.

## Resultado

<Se rellena al completar.>
