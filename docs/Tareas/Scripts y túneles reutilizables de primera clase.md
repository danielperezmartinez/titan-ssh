---
Nombre: "Scripts y túneles reutilizables de primera clase"
Estado: Pendiente
Resumen: 'Convertir scripts y túneles en entidades de primera clase con su propia pestaña en Configuración (junto a Hosts, Sesiones, Snippets y Grupos) y persistencia propia, para poder crearlos una vez y reutilizarlos en cualquier sesión. Hoy viven embebidos dentro de cada sesión; el usuario quiere una biblioteca reutilizable. Supera la intención de [[Editores de script y túnel como pantalla propia]] (que entregó editores dedicados, buen punto de partida).'
Decisiones: Amplía [[Panel de gestión de hosts y sesiones]] / [[ADR-0007 Modelo y persistencia de configuración]] y [[Scripts de inicio por sesión]]; enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]].
Bloqueada: []
Fecha de creación: 2026-09-18T19:10:00+02:00
Última modificación: 2026-09-18T19:10:00+02:00
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

## Preguntas de diseño a resolver

- **Solape con Snippets**: los snippets ya son "comandos reutilizables". ¿Los
  scripts de biblioteca (con fase/comportamiento/expect/reconnect) son un tipo
  aparte, o se unifican con snippets ampliando el modelo? Acordarlo antes de tocar
  el modelo (posible ADR).
- **Referencia vs copia** en la sesión (y qué pasa al borrar un script/túnel de
  biblioteca usado por sesiones: limpiar referencia como con hosts).
- Ámbito de los túneles reutilizables (los puertos/destino suelen ser específicos;
  valorar si el túnel de biblioteca es una plantilla).

## Verificación

<Se rellena al completar: modelo/persistencia con tests, y comprobación del flujo
(crear en su pestaña, referenciar desde varias sesiones, conectar) en dispositivo.>

## Resultado

<Se rellena al completar.>
