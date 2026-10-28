---
Nombre: 'Teclear acentos y AltGr en destinos Wayland con portal'
Estado: 'Por probar'
Estado desde: 2026-10-28
Resumen: 'En KDE y GNOME con Wayland, el escritorio remoto y el mouse pad teclean por keysyms del portal RemoteDesktop o de la API de Mutter, y el compositor solo encuentra los que están en los niveles normal y de mayúsculas de la distribución de teclado del destino: la ñ llega con la distribución española, pero las letras con tecla muerta (á, é, ü) y las de AltGr (€, @ en algunas distribuciones) no. Buscar una vía para que lleguen.'
Decisiones: 'Nace de [[Escritorio remoto en destinos Linux Wayland]] y [[Mouse pad en destinos Linux Wayland]] (ADR-0031). El usuario decide el 2026-10-22 tratarlo en esta tarea aparte en lugar de cerrar con la limitación. 2026-10-28: en GNOME el agente teclea las letras con acento como tecla muerta + letra y el € por su keysym EuroSign (solo en GNOME; KDE ya tecleaba cualquier keysym); en la app de escritorio el texto del teclado físico sale del evento tecleado y las teclas muertas se componen en la app (era lo que fallaba en KDE).'
Bloqueada: []
Prueba: 'Escribir áéíóú ü € @ ñ en KDE y GNOME con Wayland desde el móvil y desde la app de escritorio'
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-22T08:45:00+02:00
Última modificación: 2026-10-28T19:30:00+01:00
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

2026-10-28, rama `canary-input-fixes`:

- **App de escritorio (lo que fallaba en KDE)**: el mouse pad y el
  escritorio remoto tomaban el carácter de la pulsación de la tecla. AWT da
  ahí una tecla muerta como su acento suelto (ˊ) y la letra sin componer
  (`ñand´u`), y la pulsación de AltGr o Shift como U+FFFF (el cuadro antes
  del `€` y, por el portal, el salto de línea en Obsidian). Ahora el texto
  sale del evento tecleado, las teclas muertas se componen en la app si no
  lo hizo un método de entrada y las teclas sin carácter no escriben nada.
  `HardwareTypingTest` y prueba con AWT real (Xvfb, distribución `es`,
  xdotool con las teclas físicas): `echo hola ñandú € áéíóú ü @` exacto.
  Detalle en [[Escritorio remoto en destinos Linux X11]].
- **GNOME** (`tools/test-gnome`, GNOME Shell 48, distribución `es`, foot
  escribiendo en un fichero): antes llegaba `x     @ ñ Ñ ç y` de
  `x áéíóú ü € @ ñ Ñ ç y`. Mutter solo teclea keysyms que estén en una
  tecla de la distribución: á no lo está, pero ´ (tecla muerta) y a sí, y
  el `€` está como `EuroSign` (0x20ac), no como su keysym Unicode. En GNOME
  el agente manda ahora tecla muerta + letra y `EuroSign`: llega
  `áéíóú ü € @ ñ Ñ ç ÁÜ` exacto por el escritorio remoto (API de Mutter) y
  por el mouse pad (`--input`). Con distribución `us`, lo que no tiene
  tecla pierde el acento (`á` → `a`) o no sale (`ñ`), y el resto llega
  bien.
- **KDE**: el agente no cambia (KWin ya tecleaba cualquier keysym; el
  usuario lo vio desde el Pixel). El contenedor `tools/test-kde` no sirve
  en este PC (el portal no responde sin una GPU que KWin pueda usar), así
  que KDE queda para la prueba del usuario.
- Tests: `TestPortalTextKeysyms`, `go test -race ./...`, `:shared:desktopTest`.

## Cómo probarlo

**Dónde**: la canary desde el commit `b977b65` (o posterior en `main`), en el
móvil y en el PC. Destinos: un KDE Plasma y, si se puede, un GNOME, los dos
con Wayland y distribución española (`<destino-kde>`, `<destino-gnome>`).

**Preparación**: una sesión «escritorio» y otra «mouse pad» para cada
destino. La canary actualiza sola el agente al conectar.

**Pasos**:

1. Desde la app de escritorio, abrir la sesión «escritorio» del KDE, abrir
   Konsole y Obsidian y escribir con el teclado físico `áéíóú ü € @ ñ`
   (con las teclas muertas y AltGr).
2. Lo mismo desde el teclado del móvil.
3. Repetir 1 y 2 con la sesión «mouse pad».
4. Si hay un GNOME, repetir 1-3 contra él.

**Qué debe pasar**: el texto llega exacto, sin `´` suelto, sin cuadros y
sin saltos de línea al pulsar AltGr.

**Si falla**: qué paso y en qué programa, captura, versión de la canary,
escritorio y versión del destino, y su distribución de teclado.

## Resultado

<Se rellena al completar.>
