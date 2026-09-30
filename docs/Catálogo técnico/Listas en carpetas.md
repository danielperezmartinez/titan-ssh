---
Nombre: "Listas en carpetas"
Tipo: "Componente UI"
Área: "UI compartida"
Feature: "Shared UI"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/GroupedList.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Piezas Compose para mostrar una lista en las carpetas de sus grupos. groupedRows (extensión de LazyListScope) pinta las filas de GroupTree.rows sangradas SpaceLg por nivel y con su hairline; FolderRow es la fila de carpeta ([#], nombre, recuento y [+]/[-] para plegar); folderCount da el texto del recuento; NameEntryRow es la fila en línea para escribir un nombre (crear o renombrar); GroupActions son las acciones de una carpeta en Configuración (nueva entrada en el grupo, subgrupo, renombrar, mover, eliminar con confirmación); GroupPicker es el campo Grupo de los editores, con las rutas de los grupos y un [+] Nuevo grupo que lo crea y lo selecciona."
Última modificación: 2026-10-01T00:50:00+02:00
---

# Listas en carpetas

Después de descubrir esta pieza en el catálogo, consulta
[GroupedList.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/GroupedList.kt)
como fuente de verdad.

Notas de uso:

- Todo se construye con `ListRow` y `ConfirmRow` de
  [[Componentes UI compartidos]]; no hay otra fila.
- `groupedRows` añade la hairline de cada fila: el contenido que se le pasa no
  debe añadir otra.
- Las filas desplegadas de la lista las controla la lista (una a la vez), como
  en cualquier `ListRow`. `GroupActions` recibe el paso en curso (`GroupStep`)
  y lo devuelve por `onStep`.
- Usos: Configuración → Hosts y Sesiones (`GroupedConfigList` en
  `ConfigArea.kt`) y la lanzadera (solo plegar, sin carpetas vacías).

Aspecto acordado en [[Carpetas de grupos en las listas]]; estructura en
[[GroupTree]]; operaciones en [[ConfigController]].
