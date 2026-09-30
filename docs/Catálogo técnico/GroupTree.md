---
Nombre: "GroupTree"
Tipo: "Utilidad"
Área: "Configuración"
Feature: "Gestión de hosts"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/GroupTree.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.config"
Resumen: "Estructura de carpetas de los grupos de una lista (los de hosts o los de sesiones), pura y sin Compose. rows(items, groupOf, hideEmpty) da las filas de la lista como GroupedRow (Folder con su profundidad y lo que contiene contando subcarpetas, o Item): en cada nivel primero las carpetas por nombre y luego lo suelto en su orden, una carpeta plegada oculta su contenido y sin grupos devuelve la lista plana. Además path() (padre / hijo), ancestry(), descendants(), ordered() y moveTargets() (destinos válidos al mover un grupo). Tolera documentos dañados: padre inexistente o ciclo sube el grupo al nivel superior y un miembro de un grupo que falta sale suelto."
Última modificación: 2026-10-01T00:50:00+02:00
---

# GroupTree

Después de descubrir esta pieza en el catálogo, consulta
[GroupTree.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/GroupTree.kt)
como fuente de verdad.

Notas de contrato:

- Se construye con los grupos de **una** lista (`TitanConfig.groups(scope)`);
  no mezcla hosts y sesiones.
- `rows` es genérico: sirve para cualquier lista cuyos elementos digan su
  grupo con `groupOf`. La UI la pinta con `groupedRows` de
  [[Listas en carpetas]].
- El recuento de una carpeta (`itemCount`) incluye lo de sus subcarpetas, esté
  plegada o no.

Tests en `GroupsTest`. Modelo en [[Modelo de configuración]]; decisión en
[[ADR-0015 Grupos de hosts y de sesiones independientes y anidados]].
