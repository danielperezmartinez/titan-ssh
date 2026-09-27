---
Nombre: 'Scroll lateral en las pestañas de Configuración'
Estado: 'Hecha'
Resumen: 'Las barras de pestañas de la app (Hosts, Sesiones, Scripts, Grupos dentro de Configuración, y Configuración / Sesiones arriba) se desplazan en horizontal cuando no caben, en vez de partir una etiqueta en dos líneas. Con una pantalla normal se ven igual que antes. Verificado en el emulador Android con la pantalla y la letra del sistema al máximo.'
Decisiones: 'Pedida por el usuario el 2026-09-27 al cerrar [[Scripts y túneles reutilizables de primera clase]]: para las barras de pestañas prefiere scroll lateral; para los grupos de opciones, salto de línea. Al probarla apareció que la barra de arriba también se aplastaba, y se incluyó.'
Bloqueada: []
Fecha de creación: 2026-09-27T19:00:00+02:00
Última modificación: 2026-09-27T19:20:00+02:00
---

# Scroll lateral en las pestañas de Configuración

## Objetivo

Que las barras de pestañas nunca aplasten ni partan sus etiquetas: si no caben,
se desplazan en horizontal.

## Criterios de finalización

- `SubTabBar` (`ConfigArea.kt`) se desplaza en horizontal cuando las pestañas
  no caben.
- Cuando caben, se ve igual que antes.
- Verificado en el emulador Android forzando que no quepan, según el paso 0 de
  la regla 4 del [[README]].

Ampliada al probar: la barra de arriba (`AreaHeader` en `AppShell.kt`,
Configuración / Sesiones) partía "Configuración" en dos líneas en una pantalla
pequeña, así que recibe el mismo trato.

## Hecho

- `SubTabBar`: fila con `horizontalScroll` y etiquetas de una sola línea.
- `AreaHeader`: cada pestaña ocupa como mínimo su parte del ancho
  (`widthIn(min = ancho / pestañas)`), así que con sitio se ve como antes, a
  mitades. Si una etiqueta no cabe, la pestaña crece y la fila se desplaza.
- La barra de pestañas de sesiones abiertas ya se desplazaba; no cambia.

## Verificación

- Emulador `Pixel_9_Pro_XL`, build de debug:
  - Antes del cambio, con la letra del sistema a 2,0, "Grupos" se partía en
    "Grupo / s".
  - Con el cambio, a letra 2,0 y densidad 720 (una pantalla pequeña), las dos
    barras quedan en una línea, cortadas por el borde, y se desplazan con el
    dedo hasta "Grupos" y "Sesiones".
  - Con la letra y la densidad normales se ven igual que antes.
- Tests de escritorio (172) verdes; `:androidApp:assembleDebug` y
  `:desktopApp:compileKotlin` compilan.

## Resultado

Hecha el 2026-09-27. Sale en la siguiente pre-release.
