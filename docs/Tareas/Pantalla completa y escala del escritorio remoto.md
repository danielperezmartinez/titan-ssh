---
Nombre: 'Pantalla completa y escala del escritorio remoto'
Estado: 'En curso'
Estado desde: 2026-11-18
Resumen: 'Dar al visor del escritorio remoto pantalla completa y modos de escala (ajustar a la ventana o tamaño real 1:1 con minimapa, más zoom siguiendo al cursor), porque en un portátil de pantalla pequeña el destino se ve tan reducido que cuesta leer. En escritorio los controles van en la franja de estado y en una barra flotante en pantalla completa; en el móvil, tras [#] vista, con pellizco para el zoom y modo inmersivo.'
Decisiones: 'Diseño en [[Controles del visor del escritorio remoto]] y regla de la franja en [[Franja de estado según la plataforma]] (2026-11-18). Elegir el monitor queda fuera: [[Elegir el monitor en el escritorio remoto]]. La calidad de imagen, si hace falta, en [[Calidad de imagen del escritorio remoto]]. El indicador de red de la franja es el componente que ya existe (ConnectionQualityBar); no se rehace.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-18T20:25:00+01:00
Última modificación: 2026-11-18T20:25:00+01:00
---

# Pantalla completa y escala del escritorio remoto

## Objetivo

Que el escritorio remoto se pueda leer y usar cómodamente desde una pantalla
pequeña (un portátil, un móvil): pantalla completa, tamaño real 1:1 y zoom,
según [[Controles del visor del escritorio remoto]].

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
- Atajos `Ctrl+Alt+Intro`, `Ctrl+Alt+0`, `Ctrl+Alt+1`, `Ctrl+Alt++` y
  `Ctrl+Alt+-` en escritorio, sin que lleguen al destino.
- Móvil: `[#] vista` en la franja abre el panel (zoom 1x–4x, ajustar | 1:1,
  `Ctrl+Alt+Supr`, pantalla completa); pellizco para el zoom; pantalla
  completa inmersiva con `[≡]`.
- La escala y el zoom se recuerdan por sesión.
- Tests de la lógica de escala y desplazamiento; probado en escritorio y en el
  emulador.

## Verificación

<Se rellena al completar.>

## Cómo probarlo

<Al pasar a `Por probar`.>

## Resultado

<Se rellena al completar.>
