---
Nombre: "Scripts y túneles reutilizables de primera clase"
Estado: 'Hecha'
Resumen: 'Biblioteca de scripts reutilizables: una pestaña Scripts en Configuración, que sustituye a la de Snippets, con scripts que cualquier sesión usa por referencia (editar uno cambia todas las sesiones que lo usan) y que se pueden mezclar con scripts propios de la sesión. La pestaña de una sesión abierta tiene un menú para lanzar a mano sus scripts bajo demanda y los de la biblioteca. La config sube a la versión 2 con migración automática. Hecho y verificado con tests, en render y en el emulador Android, donde apareció y se corrigió que en Android no se ejecutaba ningún script. El usuario lo probó en el emulador y la dio por terminada el 2026-09-27. Los túneles salen de esta tarea: primero tienen que funcionar ([[Ejecutar los túneles de las sesiones]]).'
Decisiones: 'Decidido con el usuario el 2026-09-27 en [[ADR-0013 Biblioteca de scripts unificada con los snippets]]: unificar con los snippets, referencia viva más scripts propios, túneles fuera y menú de scripts bajo demanda en la pestaña. Amplía [[Panel de gestión de hosts y sesiones]] / [[ADR-0007 Modelo y persistencia de configuración]] y [[Scripts de inicio por sesión]]; enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]].'
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-27T18:45:00+02:00
---

# Scripts y túneles reutilizables de primera clase

## Objetivo

Que scripts y túneles sean **entidades reutilizables de primera clase**, con su
**propia pestaña** en el área de Configuración (igual que Hosts, Sesiones, Snippets
y Grupos) y **persistencia propia**. El usuario podrá crear un script o un túnel una
vez y **reutilizarlo en cualquier configuración de sesión**, en vez de tenerlos
embebidos y duplicados dentro de cada sesión como ahora.

## Aclaración (lo que se entregó vs lo que se pedía)

La tarea [[Editores de script y túnel como pantalla propia]] sacó la edición a
pantallas dedicadas (navegar → editar → volver). Eso es un buen cimiento, pero **no
es** lo que se pedía: lo que falta es que scripts y túneles sean **de biblioteca**
(pestaña propia + persistencia + referenciables desde varias sesiones), como los
hosts.

## Criterios de finalización

- **Pestañas nuevas** "Scripts" y "Túneles" en el área de Configuración
  (`ConfigArea.kt`), con su lista, alta/edición (reutilizando los editores ya
  extraídos) y borrado.
- **Persistencia propia**: `TitanConfig` guarda listas de scripts y túneles de
  biblioteca (con `id`), no solo dentro de `Session`. Bump de versión de config y
  **migración** de los scripts/túneles hoy embebidos en sesiones.
- **Reutilización**: una `Session` referencia scripts/túneles de la biblioteca por
  `id` (patrón de host referenciado). Decidir si se permite además override por
  sesión o solo referencia.
- Sin regresión en la resolución/persistencia (`ConfigModelTest`,
  `JsonFileConfigStoreTest`); añadir cobertura para las referencias y la migración.

Ajuste del 2026-09-27 (ADR-0013): los túneles salen de esta tarea y pasan a
[[Ejecutar los túneles de las sesiones]], porque todavía no se abren. Se añade
el menú de scripts bajo demanda en la pestaña de la sesión.

## Preguntas de diseño a resolver

Resueltas con el usuario el 2026-09-27; el detalle está en
[[ADR-0013 Biblioteca de scripts unificada con los snippets]].

- **Solape con Snippets**: se unifican. Los snippets pasan a ser scripts de
  biblioteca y la pestaña Snippets se sustituye por Scripts.
- **Referencia vs copia**: referencia viva, y la sesión puede tener además
  scripts propios. Borrar un script de biblioteca que se usa avisa y deja en
  cada sesión una copia propia.
- **Túneles**: fuera de esta tarea hasta que funcionen.

## Hecho (2026-09-27)

- **Modelo**: `LibraryScript` (nombre, comando, etiquetas, comportamiento,
  variables y secretos) en `TitanConfig.scripts`, en lugar de `Snippet`. Un
  `SessionScript` con `libraryScriptId` es una referencia y solo aporta fase,
  orden, activado y comportamiento al reconectar. `resolve()` rellena cada
  referencia con el contenido actual de la biblioteca (`effectiveScripts`), y
  una referencia a algo borrado no se ejecuta.
- **Migración 1 → 2** (`ConfigMigration`, sobre el JSON): cada snippet pasa a
  ser un script de biblioteca con el mismo id. Un script de sesión que venía de
  un snippet solo pasa a ser referencia si su contenido coincide con el del
  snippet; si no, queda como script propio. El fichero anterior se guarda como
  `config.json.v1.bak`.
- **Controlador**: `upsertLibraryScript` y `deleteLibraryScript`, que deja una
  copia propia en las sesiones que lo usaban.
- **UI**:
  - Pestaña **Scripts** con la lista (y en cuántas sesiones se usa cada uno) y
    su editor, que avisa antes de borrar uno en uso.
  - En el editor de script de la sesión, la sección **Origen** permite elegir
    un script de la biblioteca, hacer una copia propia o guardar un script
    propio en la biblioteca.
  - La lista de scripts de la sesión marca los de biblioteca y los enlaces
    rotos.
  - En la pestaña de una sesión conectada, `[>] scripts` abre un menú con sus
    scripts bajo demanda y los de la biblioteca, y los envía al terminal sin la
    línea centinela (`SessionTab.runScript` → `ShellAutomation.runOnDemand`).
- **De paso**:
  - `TitanSegmented` salta de línea cuando sus opciones no caben. En un móvil,
    las cinco fases del editor de script se aplastaban una encima de otra (ya
    pasaba antes de esta tarea). Se probó también un scroll lateral, pero el
    usuario lo había sugerido pensando en las pestañas (Hosts, Sesiones…), no
    en las opciones: para estas prefiere el salto de línea, porque hay sitio.
  - **En Android no se ejecutaba ningún script** (ya pasaba antes de esta
    tarea): la expresión de los `${VAR}` de `ScriptRunner` tenía una `}` sin
    escapar, que el JVM acepta y el motor ICU de Android rechaza. La excepción
    se perdía en el `runCatching` de `SessionTab`, así que los scripts fallaban
    en silencio. Corregido escapando la llave.

## Verificación

- Tests de escritorio: 172, todos verdes. Nuevos: `ConfigMigrationTest` (la
  migración con un documento v1 real, que no cambia lo que se ejecuta, y que el
  store migra al cargar y guarda la copia), 4 en `ConfigModelTest`
  (referencias, enlaces rotos, editar la biblioteca llega a todas las sesiones,
  borrar deja copia propia, menú bajo demanda) y 2 en `ScriptRunnerTest` (una
  referencia ejecuta el contenido de la biblioteca; bajo demanda no envía
  centinela). `JsonFileConfigStoreTest` cubre la biblioteca y una referencia.
- `:androidApp:assembleDebug` y `:desktopApp:compileKotlin` compilan.
- Render offscreen (a 900 y 420 px) de la pestaña Scripts, el editor de
  biblioteca, el editor de script (enlazado, propio y con enlace roto), el
  editor de sesión y la pestaña del terminal. En la pestaña, con una shell
  falsa, un clic en `[>] scripts` abre el menú. Otro clic en un script lo envía
  a la shell, sin centinela, y el script de biblioteca en fase post-inicio se
  ejecutó al conectar.
- En este PC no hay configuración de escritorio, así que la migración con datos
  reales ocurrirá la primera vez que se abra la versión nueva (en el Pixel, al
  instalar la siguiente pre-release).
- **Emulador Android** (`Pixel_9_Pro_XL`, build de debug), con el flujo del
  paso 0 de la regla 4 del [[README]]:
  - Con un `config.json` de versión 1 (dos snippets, uno de ellos insertado en
    una sesión), la app migró al abrir: los snippets aparecen en Scripts, el
    insertado queda enlazado ("en 1 sesión") y se creó `config.json.v1.bak`.
  - Contra un sshd de pruebas en Docker: al conectar se ejecutan el `cd` y el
    script de post-inicio. `[>] scripts` muestra el script de la sesión y los
    de la biblioteca, y lanzar "uso de disco" envía `df -h` sin centinela.
  - Aquí apareció el fallo de la expresión regular en Android (arriba).
  - Capturas del selector de fase con salto de línea y con scroll lateral.

- 2026-09-27: el usuario lo probó en el emulador y dio la tarea por terminada.
  El selector de fase vuelve a saltar de línea (comprobado en el emulador).

## Resultado

Hecha el 2026-09-27. Los scripts se reutilizan desde una biblioteca propia
(pestaña Scripts, que sustituye a Snippets), por referencia viva y mezclados
con scripts propios de cada sesión. Desde la pestaña de una sesión se lanzan a
mano con `[>] scripts`. La config pasa a la versión 2 con una migración que no
cambia lo que se ejecuta. De paso se arregló que en Android no se ejecutaba
ningún script. Los túneles siguen en [[Ejecutar los túneles de las sesiones]].
Sale en la siguiente pre-release; en el Pixel, la migración con datos reales
ocurrirá al instalarla.
