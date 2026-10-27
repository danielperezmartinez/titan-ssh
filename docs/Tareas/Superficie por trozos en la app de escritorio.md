---
Nombre: 'Superficie por trozos en la app de escritorio'
Estado: 'Pendiente'
Resumen: 'La app de escritorio (Skia) pinta el escritorio remoto copiando la pantalla entera a un búfer y creando una imagen nueva en cada fotograma, aunque solo cambien unas pocas baldosas. Llevarle el arreglo que ya tiene Android: la imagen en trozos que solo se actualizan, y se suben a la GPU, si cambian.'
Decisiones: ''
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T19:50:00+01:00
Última modificación: 2026-10-27T19:50:00+01:00
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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
