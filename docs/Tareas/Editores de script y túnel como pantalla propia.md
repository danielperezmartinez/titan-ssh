---
Nombre: "Editores de script y túnel como pantalla propia"
Estado: Hecha
Resumen: 'Refactor UX del editor de sesión: hoy "Añadir script" y "Añadir túnel" despliegan un formulario inline (ScriptCard/TunnelCard expandibles) metido a calzador en el editor de sesión. En su lugar, cada uno debe tener su propia pantalla de edición dedicada (como el editor de host o de grupo), a la que se navega y se vuelve, reutilizada para crear y editar. El editor de sesión solo lista y enlaza.'
Decisiones: Ajuste de [[Panel de gestión de hosts y sesiones]] y [[Scripts de inicio por sesión]]; reutiliza [[Componentes UI compartidos]] (`EditorScaffold`) y sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T17:15:00+02:00
Última modificación: 2026-09-18T18:40:00+02:00
---

# Editores de script y túnel como pantalla propia

> **Nota (2026-09-18):** esta tarea entregó los **editores dedicados** (navegar →
> editar → volver), pero el usuario aclaró que su intención real era que scripts y
> túneles fueran **entidades reutilizables con pestaña propia y persistencia**
> (como hosts/snippets/grupos). Eso se aborda en
> [[Scripts y túneles reutilizables de primera clase]], que reutiliza estos
> editores como cimiento. Esta tarea queda `Hecha` en su alcance original.

## Objetivo

Sacar la edición de scripts y de túneles del editor de sesión a **pantallas de
edición dedicadas y reutilizables**, en lugar de los formularios inline que hoy se
despliegan dentro del propio editor de sesión (`ScriptCard` / `TunnelCard`
expandibles), que quedan "a calzador". El patrón a seguir es el mismo que ya se usa
para hosts y grupos referenciados desde la sesión: navegas a una pantalla propia,
editas y vuelves.

## Contexto actual

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionEditor.kt`:

- `[+] Añadir script` añade un `SessionScript` y se edita en una `ScriptCard`
  inline expandible (fase, comportamiento, expect, reconnect, snippet, secretos…).
- `[+] Añadir túnel` hace lo mismo con `TunnelCard` (tipo, puertos, destino…).

Todo el formulario vive embebido en el editor de sesión, que queda sobrecargado.

## Criterios de finalización

- **`ScriptEditor`** y **`TunnelEditor`** como pantallas propias sobre
  `EditorScaffold` (barra `[<]` + título + `[x] Eliminar` + `[ok] Guardar`),
  reutilizadas para crear y para editar, con todos los atributos v1 actuales
  (nada de regresión funcional respecto a las cards).
- El editor de sesión pasa a **listar** scripts y túneles (fila + resumen) y a
  **navegar** a su editor; `[+] Añadir` abre el editor en modo creación.
- Se conserva el reordenado de scripts (hoy por índice) y el comportamiento de
  guardado/persistencia; sin regresiones en `ConfigModelTest`/`JsonFileConfigStoreTest`.
- Coherente con el lenguaje visual; sin chrome de Material que rompa la estética.

## Notas de diseño

- "Reutilizar igual que hosts y grupos" se refiere al **patrón de navegación**
  (pantalla propia reutilizable para alta/edición), no a convertir cada script en
  una entidad global de biblioteca: la biblioteca reutilizable global ya son los
  **snippets** ([[Panel de gestión de hosts y sesiones]]).
- Revisar la máquina de navegación del editor de sesión (cómo entra/vuelve) para
  encajar los sub-editores sin perder el estado en edición.

## Verificación

- **Build ambos targets OK** (`JAVA_HOME` al JBR, wrapper, `--console=plain`):
  `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin` → `BUILD
  SUCCESSFUL` (solo warnings preexistentes de `LocalClipboardManager` en
  `HostEditor.kt`/`TerminalView.kt`, ajenos a este cambio).
- **Tests `:shared:desktopTest`** → `BUILD SUCCESSFUL`, sin fallos ni regresión en
  `ConfigModelTest`/`JsonFileConfigStoreTest` (el modelo `SessionScript`/`Tunnel`
  no cambia; los editores solo lo editan en memoria). Los tests de integración SSH
  siguen omitidos por diseño (opt-in por variables de entorno).
- **Comprobación real del flujo** (navegar a los sub-editores, crear/editar/borrar
  script y túnel, reordenar scripts, guardar la sesión) queda como comprobación del
  usuario en dispositivo/escritorio, igual que en tareas previas.

## Resultado

Los formularios inline `ScriptCard`/`TunnelCard` del editor de sesión se han
sacado a **pantallas de edición propias y reutilizables** sobre `EditorScaffold`,
siguiendo el mismo patrón de navegación que host/grupo (navegar → editar → volver),
reutilizadas para crear y editar:

- **`ScriptEditor`** (`shared/.../ui/ScriptEditor.kt`): pantalla propia con **todos
  los atributos v1**: habilitado, etiqueta, fase, `ReconnectBehavior` (visible solo
  en fase `ON_RECONNECT`), inserción de snippet, cuerpo con `${'$'}{VAR}`,
  comportamiento (silent/wait/timeout/onFailure/delay/expect), **envVars** (editadas
  como bloque `KEY=valor` por línea y parseadas al guardar; antes no había editor
  para este campo del modelo) y secretos por referencia.
- **`TunnelEditor`** (`shared/.../ui/TunnelEditor.kt`): pantalla propia con tipo,
  etiqueta, habilitado, host/puerto de escucha y host/puerto de destino
  (LOCAL/REMOTE; oculto en SOCKS). `canSave` valida puertos en rango.
- **Editor de sesión** (`shared/.../ui/SessionEditor.kt`): ahora **lista** scripts
  y túneles con `ListRow` (marcador según habilitado + resumen) y **navega** a su
  editor; `[+] Añadir` abre el editor en **modo creación** (el elemento se añade a
  la lista en memoria solo al guardar, como hosts/grupos, no al pulsar Añadir). Se
  conserva el **reordenado** de scripts (`[^]`/`[v]` en el trailing de cada fila).
- **Máquina de navegación**: un `SubEditor` local al editor de sesión conmuta entre
  el formulario de sesión y los sub-editores sin perder el estado de la sesión en
  edición; los scripts/túneles se editan en memoria y se confirman al guardar la
  sesión (sin cambios en el modelo ni en la persistencia).

Sin regresión funcional respecto a las cards: todos sus atributos siguen presentes
(y se añade el editor de `envVars`, que faltaba). `phaseLabel`/`tunnelSummary` pasan
a `internal` en los nuevos ficheros y se reutilizan desde la lista de la sesión.

**Catálogo técnico:** no se añade ficha nueva. `ScriptEditor`/`TunnelEditor` son
editores concretos de feature (consumidores de [[Componentes UI compartidos]] +
`EditorScaffold`), del mismo tipo que los editores de host, sesión y snippet, que
por precedente **no** están catalogados (el catálogo registra primitivas y
servicios reutilizables, no cada pantalla concreta). Si se decidiera catalogarlos,
la ficha iría en Área `UI compartida` / Feature `Shared UI`.
