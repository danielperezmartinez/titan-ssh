---
Nombre: 'Quitar scripts y túneles desde la ficha de la sesión'
Estado: 'En curso'
Resumen: 'En la ficha de una sesión, cada fila de script y de túnel lleva un [x] en danger que pide confirmación en la propia fila. En un script de la biblioteca es "quitar": solo lo saca de esta sesión y el script sigue en la biblioteca y en las demás sesiones. En un script propio o un túnel es "eliminar", porque no existe en otro sitio. El botón de la cabecera del editor de script dice [x] Quitar en un script de la biblioteca y [x] Eliminar en uno propio. Como el resto de la ficha, el cambio se aplica al guardar la sesión.'
Decisiones: 'Pedida por el usuario el 2026-10-01. Se descartó que la fila abriese el script maestro de la biblioteca (y que [x] lo borrase del todo): la referencia guarda la fase, el habilitado y la reconexión propios de la sesión, que el maestro no tiene; los scripts propios no tienen maestro; y borrar el maestro dejaría rotas las demás sesiones que lo usan (ver [[ADR-0013 Biblioteca de scripts unificada con los snippets]]). Sigue [[Filas de lista con acciones contextuales desplegables]] para la confirmación en línea.'
Bloqueada: []
Fecha de creación: 2026-10-01T00:25:00+02:00
Última modificación: 2026-10-01T00:25:00+02:00
---

# Quitar scripts y túneles desde la ficha de la sesión

Petición del usuario (2026-10-01): en la ficha de una sesión, un script añadido
no se podía quitar. En realidad se podía, pero solo abriendo el script y
pulsando `[x] Eliminar` en la cabecera de su editor: estaba escondido y el
nombre hacía pensar que borraba el script de la biblioteca.

## Comportamiento acordado

- Cada fila de script de la ficha lleva `[x]` (en `danger`) tras `[^]` `[v]`.
  Al pulsarlo, la fila se cambia por una confirmación en línea:
  - Script de la biblioteca (tiene `libraryScriptId`, exista o no el maestro):
    "¿Quitar de la sesión?". Si el maestro existe, con la línea "Sigue en la
    biblioteca". El maestro no se toca.
  - Script propio: "¿Eliminar el script?", porque no existe en otro sitio.
- Las filas de túneles llevan el mismo `[x]` con "¿Eliminar el túnel?". Los
  túneles siempre son propios de la sesión.
- En la cabecera del editor de script, el botón es `[x] Quitar` en un script de
  la biblioteca y `[x] Eliminar` en uno propio. Es corto para que quepa en el
  móvil.
- Pulsar la fila sigue abriendo el script **dentro de la sesión**, no el
  maestro. Se descartó abrir el maestro por lo que se explica en
  `Decisiones`.
- Como el resto de la ficha, el cambio es en memoria: se aplica con
  `[ok] Guardar` y se descarta con `[<]`.

## Implementación

- `ui/SessionEditor.kt`: `SessionForm` guarda qué fila está confirmando
  (`confirmingRemoval`, una a la vez) y la cambia por un `ConfirmRow`. Recibe
  `onTunnels` para poder quitar túneles igual que ya recibía `onScripts`.
- `ui/ScriptEditor.kt`: la cabecera usa `[x] Quitar` si el borrador enlaza a la
  biblioteca y `[x] Eliminar` si es propio.
- `ui/Components.kt`: `EditorScaffold` acepta `deleteLabel` (por defecto
  `[x] Eliminar`, así que los demás editores no cambian). Anotado en
  [[Componentes UI compartidos]].

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:androidApp:assembleDebug` y `:shared:desktopTest` (255 tests, 0 fallos) →
  `BUILD SUCCESSFUL`.
- Emulador `Pixel_9_Pro_XL` (build de debug), 2026-10-01, sesión "proyecto
  demo" (un script de la biblioteca, uno propio y cinco túneles):
  - `[x]` en el de la biblioteca → "¿Quitar de la sesión?" / "Sigue en la
    biblioteca"; en el propio → "¿Eliminar el script?"; en un túnel →
    "¿Eliminar el túnel?". `[<] No` devuelve la fila.
  - La cabecera del editor dice `[x] Quitar` en el de la biblioteca y
    `[x] Eliminar` en el propio.
  - Quitar el de la biblioteca y guardar: el `config.json` ya no tiene la
    referencia en la sesión y el script sigue en la biblioteca.
  - Eliminar el propio y salir con `[<]` sin guardar: al volver a abrir la
    ficha, sigue ahí.
  - Después se restauró la config del emulador desde una copia
    (`config.pre-remove.bak`).
- **Pendiente**: que el usuario lo pruebe en el emulador y lo dé por bueno.
