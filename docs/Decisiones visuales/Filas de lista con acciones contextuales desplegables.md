---
Nombre: 'Filas de lista con acciones contextuales desplegables'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'Las filas de lista (ListRow) tienen tres zonas con acción propia y opcional: la fila (pulsar y mantener pulsada, o clic derecho en escritorio), el marcador y la zona derecha. Una fila puede desplegar debajo sus acciones contextuales, que son más filas, sobre superficie plana surface y sangradas 24dp. Solo hay una fila desplegada por lista; mientras lo está, su marcador va en accent. Lo destructivo se confirma en la propia fila, sin diálogos.'
Decisión: 'Acciones contextuales en línea bajo la fila, reveladas desde el marcador o con pulsación larga, construidas con el mismo componente de fila; confirmación de lo destructivo en línea con [<] No · [x] Sí.'
Consecuencias: 'Cualquier lista puede ofrecer acciones sin menús flotantes ni diálogos de Material, manteniendo la estética de terminal. La acción principal de la fila (p. ej. conectar) queda a un toque y las secundarias a uno más. Un marcador pulsable necesita toda la altura de la fila como zona táctil.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-29T10:00:00+02:00
Última modificación: 2026-09-29T11:00:00+02:00
---

# Filas de lista con acciones contextuales desplegables

Pedida por el usuario el 2026-09-28/29
([[Acciones contextuales en las filas de lista]]). Se construye sobre
[[Componentes UI compartidos]] y respeta
[[Vocabulario ASCII ampliado y disciplina de color]].

## Zonas de la fila

- **Fila completa**: pulsar y mantener pulsada. En escritorio, el clic
  derecho equivale a mantener pulsada.
- **Marcador**: zona táctil de toda la altura de la fila. Si no tiene acción
  propia, forma parte de la fila.
- **Zona derecha**: contenido libre (un botón, glifos de reordenar…).

Ninguna zona reacciona si no se configura; el componente no decide qué gesto
hace qué.

## Acciones contextuales

- Se muestran bajo la fila, con una animación de despliegue vertical, sobre
  `surface` y sangradas `SpaceXl` (24dp). Son filas normales con su marcador.
- Una sola fila desplegada por lista. La fila desplegada lleva su marcador en
  `accent` (selección activa), salvo que esté en un estado de error real, que
  manda.
- Lo destructivo se confirma en la misma fila: la acción se sustituye por la
  pregunta con `[<] No` (secundario) y `[x] Sí` (destructivo). Sin diálogos.

## Primer uso: lanzadera de Sesiones

- Fila y `[>] Lanzar` → conectar.
- Marcador y pulsación larga → desplegar `[~] Editar`, `[+] Duplicar` y
  `[x] Eliminar` (en `danger`).
