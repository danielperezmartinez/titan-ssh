---
Nombre: 'Confirmar el borrado en los editores'
Estado: 'En curso'
Resumen: 'El botón [x] Eliminar / [x] Quitar de la barra de los editores (host, sesión, script de la biblioteca, script de una sesión y túnel) borraba con un solo toque. Ahora cambia la barra por una confirmación en línea ([<] No · [x] Sí) que avisa de las consecuencias: las sesiones que se quedan sin host, si la sesión sigue viva en el destino (entonces el botón dice "[x] Eliminar y terminarla", como en el lanzador) o cuántas sesiones usan el script. De paso, ConfirmRow baja sus botones bajo la pregunta cuando no cabe a su lado; antes, en el móvil, la pregunta del lanzador se aplastaba hasta salir una letra por línea.'
Decisiones: 'Pedida por el usuario el 2026-10-01, tras borrar una sesión sin querer desde su editor. La confirmación vive en EditorScaffold, así que la tienen todos los editores sin repetir código. Sigue [[Filas de lista con acciones contextuales desplegables]]: confirmación en línea, sin diálogos. El aviso propio del script de la biblioteca (que antes solo salía si alguna sesión lo usaba) se une a la confirmación general.'
Bloqueada: []
Fecha de creación: 2026-10-01T11:40:00+02:00
Última modificación: 2026-10-01T12:10:00+02:00
---

# Confirmar el borrado en los editores

Petición del usuario (2026-10-01): borró una sesión sin querer al pulsar el
botón de eliminar, que no pedía confirmación. Se repasaron todos los botones de
borrar de la app.

## Repaso

Ya confirmaban en la propia fila: Eliminar y Terminar en el destino del
lanzador, Eliminar de los grupos, el `[x]` de las filas de scripts y túneles de
la ficha de la sesión, y Terminar, Detener y Actualizar del panel del agente.

No confirmaban, porque `EditorScaffold` llamaba a `onDelete` directamente:

| Editor | Consecuencia |
|---|---|
| Sesión | Borra la sesión y, si estaba viva, la termina en el destino. |
| Host | Borra el host; sus sesiones se quedan sin host y los hosts que saltaban por él pierden el salto. |
| Script de la biblioteca | Solo preguntaba si alguna sesión lo usaba. |
| Script de una sesión | Se quitaba sin preguntar (se recuperaba saliendo sin guardar la sesión). |
| Túnel de una sesión | Igual que el script de una sesión. |

## Comportamiento acordado

- `[x] Eliminar` (o `[x] Quitar`) cambia la barra del editor por una
  confirmación en línea: marcador `[x]` en `danger`, la pregunta, un aviso
  opcional, `[<] No` y el botón de confirmar. `[<] No` devuelve la barra.
- Textos por editor:
  - Sesión: "¿Eliminar la sesión?". Si sigue viva en el destino, "Sigue viva
    en el destino" y `[x] Eliminar y terminarla`, igual que en el lanzador.
  - Host: "¿Eliminar el host?" y, si aplica, "N sesiones se quedan sin host"
    y "N hosts dejan de saltar por él".
  - Script de la biblioteca: "¿Eliminar el script?" y, si lo usan sesiones,
    "Lo usan N sesiones: cada una se queda con una copia propia".
  - Script de una sesión: "¿Quitar de la sesión?" ("Sigue en la biblioteca")
    o "¿Eliminar el script?", con los mismos textos que el `[x]` de su fila.
  - Túnel: "¿Eliminar el túnel?".
- `ConfirmRow`: los botones van a la derecha de la pregunta si la pregunta y el
  subtítulo caben enteros en una línea a su lado; si no, bajan debajo,
  alineados a la derecha.

## Implementación

- `ui/Components.kt`: `EditorScaffold` guarda `confirmingDelete` y acepta
  `deleteQuestion`, `deleteSubtitle` y `deleteConfirmLabel`. `ConfirmRow` deja
  de usar la zona derecha de `ListRow` y pasa a un `Layout` propio que decide
  si los botones caben en la línea (mismos márgenes que `ListRow`).
- `terminal/AgentManager.kt`: `isLive(session)` dice si la sesión aparece en
  el último informe de su agente. `AppShell` → `ConfigArea` → `SessionEditor`
  lo reciben como `isSessionLive` / `isLiveOnDestination`.
- `ui/HostEditor.kt`, `ui/SessionEditor.kt`, `ui/LibraryScriptEditor.kt` (sin
  su confirmación propia), `ui/ScriptEditor.kt` y `ui/TunnelEditor.kt`: pasan
  sus textos.
- Catálogo: [[Componentes UI compartidos]].

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:shared:desktopTest` y `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- Emulador `Pixel_9_Pro_XL` (build de debug), 2026-10-01:
  - Los cinco editores muestran su pregunta y su aviso; `[<] No` devuelve la
    barra sin borrar nada.
  - "demo (agente) (copia)", viva en el destino: "Sigue viva en el destino" y
    `[x] Eliminar y terminarla`, con los botones bajo la pregunta. En el
    lanzador, la misma confirmación ya no se aplasta.
  - "proyecto demo": la pregunta corta y `[x] Sí` en una sola línea.
  - `[x] Sí` en un túnel lo quita de la ficha. En "proyecto demo" borra la
    sesión de la lista y del `config.json`. Después se restauró la config del
    emulador desde una copia (`config.pre-confirm.bak`).
- Escritorio: solo compilación y tests; no se ha mirado la ventana.
- **Pendiente:** revisión del usuario.
