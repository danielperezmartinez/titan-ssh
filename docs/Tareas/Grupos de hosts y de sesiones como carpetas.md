---
Nombre: 'Grupos de hosts y de sesiones como carpetas'
Estado: 'Hecha'
Resumen: 'Los grupos se separan en grupos de hosts y grupos de sesiones, independientes y anidables (parentId), y cada uno es una carpeta plegable en su lista: Configuración → Hosts, Configuración → Sesiones y la lanzadera. Lo que no tiene grupo queda suelto, sin carpeta Otros, así que sin grupos la lista es la de siempre. Los grupos se crean y gestionan dentro de su lista (se quita la pestaña Grupos) y desde el editor; las carpetas recuerdan si quedaron plegadas. Configuración versión 3 con migración que no cambia el grupo de nadie. Verificado con tests y en el emulador Android.'
Decisiones: 'Pedida por el usuario el 2026-10-01. Elegido por el usuario: los grupos se crean dentro de su propia lista, las carpetas recuerdan su estado plegado y se mantiene el anidamiento con parentId. A criterio del agente: sin carpeta Otros (lo suelto va después de las carpetas). Modelo en [[ADR-0015 Grupos de hosts y de sesiones independientes y anidados]]; aspecto en [[Carpetas de grupos en las listas]].'
Bloqueada: []
Fecha de creación: 2026-10-01T00:00:00+02:00
Última modificación: 2026-10-01T00:50:00+02:00
---

# Grupos de hosts y de sesiones como carpetas

## Objetivo

Que los grupos sirvan para organizar: separar los grupos de hosts de los de
sesiones y mostrar cada lista agrupada en carpetas.

## Situación de partida

- `Group(id, name, parentId)` en `config/Model.kt`, una sola lista
  `TitanConfig.groups` compartida. `Host.groupId` y `Session.groupId` apuntaban
  a ella. `parentId` no se usaba en ningún sitio.
- La pestaña Grupos de Configuración solo creaba y borraba (sin confirmar).
- El grupo solo aparecía en el subtítulo de la fila del host; la lista de
  sesiones y la lanzadera lo ignoraban.

## Decisiones con el usuario

1. Los grupos se crean **dentro de su lista** (no en una pestaña aparte).
2. Las carpetas **recuerdan** si quedaron plegadas.
3. Se **mantiene `parentId`**: los grupos se anidan.
4. A criterio del agente: **sin carpeta "Otros"**. Lo que no tiene grupo va
   suelto, después de las carpetas; sin grupos no cambia nada.

## Qué se hizo

- **Modelo** (`Model.kt`): `GroupScope` (`HOSTS`/`SESSIONS`), `Group` con
  `collapsed`, `TitanConfig.hostGroups` y `sessionGroups` con
  `groups(scope)`/`withGroups(scope, …)`. Versión 3.
- **Migración 2 → 3** (`ConfigMigration`): cada grupo va a la lista de lo que
  contiene (con sus padres), con el mismo id; uno sin uso va a las dos.
- **`GroupTree`** (`config/GroupTree.kt`): filas de la lista en carpetas,
  rutas, descendientes, destinos al mover; tolera documentos dañados.
- **`ConfigController`**: `createGroup`, `renameGroup`, `setGroupCollapsed`,
  `moveGroup` (rechaza ciclos) y `deleteGroup` (sube el contenido al padre).
- **UI** (`ui/GroupedList.kt`): `groupedRows`, `FolderRow`, `NameEntryRow`,
  `GroupActions`, `GroupPicker`. `ConfigArea` pierde la pestaña Grupos y sus
  listas de hosts y sesiones pasan a `GroupedConfigList`. La lanzadera muestra
  las carpetas de sesiones (sin las vacías). Los editores usan `GroupPicker` y
  aceptan un grupo inicial ("Nuevo host/sesión en este grupo").
- Catálogo: [[GroupTree]], [[Listas en carpetas]], y actualizados
  [[ConfigController]] y [[Modelo de configuración]].

## Verificación

- `:shared:desktopTest` completo en verde, con `GroupsTest` nuevo (10 tests:
  orden y sangrado, plegado, carpetas vacías, documento dañado, rutas y
  destinos, crear/renombrar/plegar, mover con ciclos, borrar subiendo el
  contenido, migración 2 → 3) y los de modelo, migración y almacén adaptados.
  Compilan `:desktopApp:compileKotlin` y `:androidApp:assembleDebug`.
- Emulador `Pixel_9_Pro_XL`, build de debug sobre la configuración real de
  pruebas (copia previa en `files/titan-config/config.pre-groups.bak`):
  - Sin grupos, la lanzadera y las listas se ven como antes.
  - Crear grupo y subgrupo en línea; poner sesiones en ellos desde el editor
    (rutas `demo / nivel3`); crear un grupo desde el editor, que queda
    elegido.
  - Carpetas con recuento, sangrado por nivel y `[+]`/`[-]`; plegar en
    Configuración se ve en la lanzadera y se conserva tras cerrar la app.
  - Lanzar una sesión desde dentro de una carpeta: conecta.
  - Grupos de hosts independientes: en Hosts solo aparece el suyo y el editor
    de host solo ofrece grupos de hosts.
  - Mover un grupo dentro de un subgrupo, y de vuelta fuera de carpetas; un
    grupo no se ofrece como destino dentro de sus propios subgrupos.
  - Eliminar un subgrupo avisa "Lo que contiene pasa a demo" y lo sube sin
    perder sesiones. Renombrar.
  - El fichero guardado es la versión 3 y queda `config.json.v2.bak`.
- Capturas fuera del repositorio, en la carpeta temporal del agente
  (`titan-groups`).

## Pendiente

- Que el usuario lo pruebe en el emulador (queda abierto con grupos de
  ejemplo: "demo docker" y "windows" en sesiones, "docker" en hosts).
