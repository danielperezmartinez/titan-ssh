---
Nombre: 'Superficie por trozos en la app de escritorio'
Estado: 'Hecha'
Resumen: 'La app de escritorio (Skia) pintaba el escritorio remoto convirtiendo y copiando la pantalla entera en cada fotograma, aunque solo cambiaran unas pocas baldosas. Ahora, como en Android, la superficie va en imágenes de 256×256 que solo se rehacen, y se suben a la GPU, si cambian. Medido en una ventana Compose real contra tools/test-x11 con scroll continuo (agente de main): CPU del proceso 38-60 % → 14-19 % de un núcleo, write por fotograma p50 12-18 ms → 0,5-0,7 ms, mismos fps (20-22, los que manda el agente).'
Decisiones: 'Trozos como Image inmutables de Skia (no Bitmap ni ImageBitmap de Compose): un trozo que no cambia conserva su textura. El lector convierte a BGRA solo el rectángulo cambiado; el hilo de UI copia filas y rehace los trozos al dibujar. Se dibuja con el Paint sin antialias (con antialias se ven costuras entre trozos) y con el origen redondeado a píxel entero, como el drawImage de Compose, para que a escala 1 la imagen sea idéntica.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T19:50:00+01:00
Última modificación: 2026-10-27T21:50:00+01:00
---

# Superficie por trozos en la app de escritorio

## Objetivo

En [[Escritorio remoto fluido al mover el ratón]] la superficie de Android
pasó a bitmaps de 256×256 que solo se actualizan donde cambia el
fotograma. La de escritorio (`SkiaScreenSurface`) sigue convirtiendo y
copiando la pantalla entera en cada fotograma (`Image.makeRaster`) y la GPU
la vuelve a subir entera. No hay tirones medidos en escritorio, pero el
coste crece con el tamaño de la pantalla remota.

## Análisis

- La API ya está preparada: `drawScreenSurface` dibuja la superficie, así
  que el cambio queda dentro de `ScreenSurfaces.desktop.kt`.
- Igual que en Android: el lector deja el rectángulo cambiado en un búfer
  publicado y el hilo de UI pone al día solo los trozos que lo tocan.

## Criterios de finalización

- La superficie de escritorio solo actualiza los trozos que cambian.
- Medido antes y después en la app de escritorio con una pantalla remota
  que cambie (p. ej. `tools/test-x11` con scroll continuo).

## Verificación

- **Banco de medida** (desechable, fuera del repositorio): un test de
  escritorio que abre una ventana Compose de verdad (Skia con GPU, la del
  escritorio del equipo de desarrollo), se conecta a `tools/test-x11`
  (1280×800) por `ScreenTransport` con el agente de `main` (captura X11 por
  `GetImage`, sin MIT-SHM), mete la pantalla en `RemoteScreen` y la dibuja
  cada fotograma con `drawScreenSurface` como `RemoteScreenCanvas`
  (escalada a la ventana, filtro `Medium`). Dentro del contenedor, un xterm
  de 100×30 hace `ls -la` sin parar. 5 s de calentamiento y 30 s de
  medida: CPU del proceso (en % de un núcleo), tiempo de `write` (hilo
  lector) y de `drawScreenSurface` (hilo de UI) por fotograma.
- **Resultados** (antes y después alternados; la segunda tanda, con el
  equipo más cargado, da más CPU en las dos):

  | | antes | después |
  |---|---|---|
  | Fotogramas por segundo | 20,2 · 22,2 · 22,5 | 20,3 · 20,5 · 22,4 |
  | CPU del proceso | 37,8 · 58,1 · 59,8 % | 14,1 · 14,2 · 18,5 % |
  | `write` p50 / p99 | 12,0-18,5 / 16,7-28,2 ms | 0,46-0,67 / 1,5-2,3 ms |
  | `write`, total por segundo | 247-427 ms | 11-16 ms |
  | Dibujo en el hilo de UI p50 / p99 | 0,04 / 0,06-0,12 ms | 0,29-0,36 / 0,69-0,83 ms |

  Los fotogramas son los que manda el agente (el ritmo de captura de X11),
  igual antes y después. Antes, el dibujo solo registraba la imagen y la
  subida entera a la GPU pasaba después, fuera de lo medido; ahora el hilo
  de UI rehace los trozos cambiados (~0,3 ms) y solo esos se suben.
- **Píxeles**: render fuera de pantalla (`ImageComposeScene`) de una
  superficie de 700×530 con bloques de color, zonas translúcidas y cuatro
  escrituras parciales que cruzan bordes de trozos, comparado con la
  implementación anterior: a escala 1, idéntica píxel a píxel; a 0,73
  (`Medium`) y a 1,37 con alfa 0,45 (`Low`), sin costuras a la vista y
  diferencias solo en el ruido aleatorio y en la fila de borde de cada
  trozo (el filtro no mira el trozo vecino). Con el antialias por defecto
  del `Paint` de Skia se veían costuras oscuras entre trozos: se quitó.
- Tests: `:shared:desktopTest` de pantalla y mouse pad en verde.

## Resultado

`SkiaScreenSurface` (`ScreenSurfaces.desktop.kt`) guarda la pantalla en
imágenes inmutables de Skia de 256×256. `write` convierte a BGRA solo el
rectángulo cambiado y lo acumula; al dibujar, el hilo de UI rehace solo los
trozos que toca ese rectángulo y Skia conserva en la GPU las texturas de los
demás. Con scroll en una parte de la pantalla, la app gasta unas tres veces
menos CPU y la escritura de cada fotograma pasa de 12-18 ms a menos de 1 ms.
