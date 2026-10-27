---
Nombre: 'Scroll del escritorio remoto en KDE'
Estado: 'Hecha'
Resumen: 'Desde el Pixel, el scroll con dos dedos en un escritorio KDE Wayland (remoto o mouse pad) apenas mueve nada: en Konsole hacen falta muchísimos gestos para avanzar un poco y en Obsidian va lento. Causa: el agente manda las muescas con NotifyPointerAxisDiscrete y el portal de KDE (xdg-desktop-portal-kde 6.8) pasa los steps tal cual como valor del eje de fake_input, donde una muesca real vale 15, así que cada muesca llega como 1/15. Arreglo: en KDE mandar scroll continuo (NotifyPointerAxis) a 10 unidades por muesca (los 120 de ángulo de una muesca en Qt), también las fracciones. Probado por el usuario en el Pixel con la canary del 2026-10-27: va muy bien.'
Decisiones: 'En KDE el agente manda el scroll con NotifyPointerAxis tal cual llega, fracciones incluidas, a 10 unidades por muesca: Qt convierte cada unidad continua en 12 de ángulo, así que 10 dan los 120 de una muesca de rueda. GNOME (portal y API de Mutter) sigue con muescas discretas, que trata bien.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T21:00:00+01:00
Última modificación: 2026-10-27T22:15:00+01:00
---

# Scroll del escritorio remoto en KDE

## Objetivo

El usuario se conecta desde el Pixel a un PC con KDE Plasma (Wayland) con el
escritorio remoto ([[Escritorio remoto a través del agente]],
[[Escritorio remoto en destinos Linux Wayland]]) y el scroll con dos dedos
casi no avanza: en Konsole tiene que repetir el gesto muchísimas veces para
que se mueva un poco, y en Obsidian (Electron) va algo mejor, pero lento.

## Criterios de finalización

- En KDE, una muesca que manda la app mueve lo mismo que una muesca de una
  rueda física, medido con una ventana Qt que registra los eventos de rueda.
- El scroll sigue al dedo también por debajo de una muesca entera.
- GNOME (portal y API de Mutter), wlroots, X11 y Windows no cambian.
- El usuario lo prueba en el Pixel.

## Análisis

Camino: dos dedos en `MousepadView` → `MousepadMotion.scroll` (una muesca de
120 unidades por cada 40 dp) → frame `SCROLL` → `inject.Portal.Scroll`, que
acumula y manda una llamada `NotifyPointerAxisDiscrete(eje, steps)` por muesca
entera.

En xdg-desktop-portal-kde 6.8.1, `NotifyPointerAxisDiscrete` llama a
`requestPointerAxisDiscrete(eje, steps)`, que manda a KWin
`fake_input.axis(eje, wl_fixed(steps))`. KWin lo entrega como scroll continuo
de valor `steps`, sin `v120` y con origen desconocido. Una muesca de una rueda
física vale 15 (y 120 en `v120`), así que cada muesca llega como 1/15. Konsole
necesita 15 de las nuestras (unos 600 dp de dedo) para moverse una muesca.

`NotifyPointerAxis(dx, dy)` llega a la misma `fake_input.axis` con `-dy` en
vertical y `dx` en horizontal, y admite fracciones.

GNOME no tiene el fallo: Mutter trata `steps` como muescas, tanto por su API
como por el portal de GNOME.

Medido en el KDE del PC de desarrollo (Plasma 6.8.1) con una ventana QML a
pantalla completa que registra `angleDelta` y `pixelDelta` de cada evento de
rueda: 3 muescas del agente actual llegan como un solo evento de
`pixel 3, angle 36` (+ al subir, − al bajar; el sentido es correcto). Una
muesca de la rueda física llega como `pixel 15, angle 120`. Es decir, cada
muesca llega como 1/15 en píxeles y 1/10 en ángulo. Qt da 12 de ángulo por
unidad continua.

## Cambio

- `portal.Session.PointerAxis` (`NotifyPointerAxis`).
- `inject.Portal.SmoothScroll`: si está puesto, `Scroll` lo llama en cada
  frame con `dx, dy × 10 / 120`, sin acumular ni redondear. Solo lo ponen
  `screen_portal.go` y `input_portal.go` cuando la sesión es KDE.
- Test `TestPortalSmoothScroll`.

## Verificación

- `go test ./internal/inject/ ./cmd/titan-agent/`: pasa.
- En el KDE del PC de desarrollo, con la misma ventana QML y un agente de
  prueba por `--screen` (`titan-screenprobe -scroll`), 3 muescas:
  - Agente anterior: `angle 36, pixel 3` (un evento).
  - Con el arreglo: `angle 360, pixel 30` al subir y `-360, -30` al bajar,
    lo mismo en ángulo que 3 muescas de la rueda física.
- El usuario lo prueba desde el Pixel con la canary del 2026-10-27
  (commit `b92a442`): va muy bien.

## Resultado

En KDE el agente manda el scroll del escritorio remoto y del mouse pad con
`NotifyPointerAxis`, a 10 unidades por muesca y sin redondear a muescas
enteras, así que una muesca de la app mueve lo mismo que una de la rueda
física y el scroll sigue al dedo. Los demás destinos no cambian.
