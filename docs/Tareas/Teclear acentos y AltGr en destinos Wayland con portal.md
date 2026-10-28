---
Nombre: 'Teclear acentos y AltGr en destinos Wayland con portal'
Estado: 'Pendiente'
Resumen: 'En KDE y GNOME con Wayland, el escritorio remoto y el mouse pad teclean por keysyms del portal RemoteDesktop o de la API de Mutter, y el compositor solo encuentra los que están en los niveles normal y de mayúsculas de la distribución de teclado del destino: la ñ llega con la distribución española, pero las letras con tecla muerta (á, é, ü) y las de AltGr (€, @ en algunas distribuciones) no. Buscar una vía para que lleguen.'
Decisiones: 'Nace de [[Escritorio remoto en destinos Linux Wayland]] y [[Mouse pad en destinos Linux Wayland]] (ADR-0031). El usuario decide el 2026-10-22 tratarlo en esta tarea aparte en lugar de cerrar con la limitación.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-22T08:45:00+02:00
Última modificación: 2026-10-28T17:30:00+01:00
---

# Teclear acentos y AltGr en destinos Wayland con portal

## Objetivo

Que el texto que se escribe desde la app (mouse pad y escritorio remoto)
llegue entero a un escritorio KDE o GNOME con Wayland, también los caracteres
que en la distribución del destino van con tecla muerta o con AltGr.

## Contexto

Comprobado en GNOME Shell 48 (`tools/test-gnome`) con la distribución
española: `NotifyKeyboardKeysym` teclea «ñÑ Az», pero no «áé» ni «€». En X11 el
agente toma prestado un código de tecla libre, y en wlroots usa su propio mapa
de teclado; con el portal y Mutter el mapa es el del escritorio.

En KDE Plasma con Wayland (prueba del usuario del 2026-10-28, canary
1.3.1-canary.42) depende de desde dónde se escribe. Desde el teclado del
Pixel, `echo ñandú €` llega exacto. Desde la app de escritorio (en un Linux
X11), `ñandú` llega como `ñand´u`, con la tilde suelta antes de la letra. AltGr
mete un salto de línea en Obsidian, aunque el `€` sale; en Konsole aparece un
cuadro sin glifo antes del `€`. Así que en KDE falla sobre todo el camino de
las teclas físicas de la app de escritorio.

Vías posibles, por valorar:

- Mandar la tecla muerta y luego la letra (´ + a) cuando la distribución la
  tenga; hace falta conocer la distribución del destino (gsettings de GNOME,
  `kxkbrc` de KDE) o probar.
- AltGr: mandar `ISO_Level3_Shift` alrededor del keysym, si el compositor lo
  resuelve así.
- Pegar el texto por el portapapeles del portal RemoteDesktop (v2), con el
  cuidado de no pisar el portapapeles del usuario.

## Criterios de finalización

- En KDE y GNOME con Wayland y distribución española: «áéíóú ü € @ ñ»
  llegan desde el mouse pad y el escritorio remoto.
- Con una distribución sin esas teclas, lo que no se pueda teclear no rompe el
  resto del texto.
- Confirmado por el usuario en un escritorio Wayland real.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
