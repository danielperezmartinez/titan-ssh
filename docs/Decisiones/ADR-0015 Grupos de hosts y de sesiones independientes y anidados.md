---
Nombre: 'Grupos de hosts y de sesiones independientes y anidados'
Número: 15
Estado: 'Aceptada'
Resumen: 'Los grupos dejan de ser una lista compartida por hosts y sesiones. TitanConfig tiene dos listas independientes, hostGroups y sessionGroups: un host solo va en un grupo de hosts y una sesión en uno de sesiones. Los grupos se anidan con parentId dentro de su misma lista y recuerdan si su carpeta quedó plegada (collapsed). Borrar un grupo sube lo que contiene a su padre. La configuración pasa a la versión 3; la migración reparte cada grupo según lo que contiene, conserva los ids y no cambia el grupo de ningún host ni sesión.'
Decisión: 'Separar los grupos por ámbito (GroupScope HOSTS / SESSIONS) en dos listas del documento, mantener el anidamiento por parentId y guardar el estado plegado en el propio grupo. Integridad en ConfigController: no se mueve un grupo dentro de sí mismo ni de sus subgrupos, y borrar nunca borra hosts ni sesiones.'
Consecuencias: 'Cada lista se organiza a su manera sin que un grupo de hosts aparezca entre los de sesiones. Un mismo id puede existir en las dos listas (lo deja la migración); las referencias siempre se leen en la lista de su ámbito. El estado plegado vive en el documento de configuración y se comparte entre la lanzadera y Configuración. La UI tolera documentos dañados: un padre que falta o un ciclo sube el grupo al nivel superior, y un miembro de un grupo inexistente aparece suelto.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-01T00:50:00+02:00
Última modificación: 2026-10-01T00:50:00+02:00
---

# ADR-0015 · Grupos de hosts y de sesiones independientes y anidados

## Contexto

[[ADR-0007 Modelo y persistencia de configuración]] modeló un único tipo de
grupo (`Group` con `parentId`) en `TitanConfig.groups`, compartido por hosts y
sesiones. En la práctica no organizaba nada: solo aparecía como texto en el
subtítulo del host, y `parentId` no se usaba.

El 2026-10-01 el usuario pidió que los grupos organicen de verdad las listas
([[Grupos de hosts y de sesiones como carpetas]]): grupos independientes para
hosts y para sesiones, y que se mantenga el anidamiento para organizar mejor.

## Decisión

1. **Dos listas.** `TitanConfig.hostGroups` y `TitanConfig.sessionGroups`.
   `Host.groupId` apunta a la primera y `Session.groupId` a la segunda.
   `GroupScope` (`HOSTS` / `SESSIONS`) elige la lista en el controlador
   (`groups(scope)`, `withGroups(scope, …)`).
2. **Anidamiento.** `Group.parentId` apunta a otro grupo de la misma lista;
   `null` es el nivel superior. Sin límite de profundidad.
3. **Estado plegado.** `Group.collapsed` guarda si la carpeta quedó plegada. Es
   estado de la interfaz, pero el usuario quiere que se recuerde entre
   arranques y el documento de configuración ya es el sitio donde persiste lo
   suyo.
4. **Integridad** (en `ConfigController`):
   - `moveGroup` rechaza mover un grupo dentro de sí mismo, de uno de sus
     subgrupos o de un grupo que no existe.
   - `deleteGroup` sube a su padre (o fuera de carpetas) los subgrupos y los
     miembros del grupo borrado. Borrar un grupo nunca borra hosts ni sesiones.
5. **Migración 2 → 3** (`ConfigMigration`): cada grupo va a la lista de lo que
   contiene, directamente o a través de un subgrupo, junto con sus padres. Uno
   que contiene de los dos va a ambas listas con el mismo id. Uno que no
   contiene nada va también a las dos, para no perderlo. Ningún host ni sesión
   cambia de grupo. Se guarda la copia `config.json.v2.bak` como en la
   migración anterior.

## Alternativas descartadas

- **Un campo `scope` en cada grupo, en una sola lista.** Obliga a filtrar en
  cada uso y permite referencias cruzadas (un host en un grupo de sesiones)
  que luego hay que validar.
- **Quitar `parentId`** (un solo nivel). Lo propuso el agente por sencillez; el
  usuario prefirió mantenerlo.
- **Estado plegado fuera del documento** (memoria o un fichero aparte). En
  memoria no se recuerda entre arranques; un fichero aparte añade otra
  persistencia para un solo booleano por grupo.

## Consecuencias

- Las dos listas se organizan por separado.
- Los ids solo son únicos dentro de su lista.
- Cómo se ve y se gestiona en la UI:
  [[Carpetas de grupos en las listas]].
