---
Nombre: 'Acciones contextuales en las filas de lista'
Estado: 'Hecha'
Resumen: 'ListRow pasa a ser una fila configurable por zonas: pulsar la fila, mantenerla pulsada (clic derecho en escritorio), pulsar el marcador y una zona derecha libre; cada zona solo reacciona si se le da una acción. Puede desplegar debajo contenido contextual, que son otras ListRow. Primer uso: en la lista de Sesiones, fila y [>] Lanzar conectan; marcador y pulsación larga despliegan Editar, Duplicar y Eliminar (con confirmación en la propia fila).'
Decisiones: 'Aplica [[Filas de lista con acciones contextuales desplegables]]; añade [~] a [[Vocabulario ASCII ampliado y disciplina de color]].'
Bloqueada: []
Fecha de creación: 2026-09-29T10:00:00+02:00
Última modificación: 2026-09-29T11:40:00+02:00
---

# Acciones contextuales en las filas de lista

Petición del usuario (2026-09-28/29). `ListRow` (ver
[[Componentes UI compartidos]]) debe ser un componente genérico y **todo
configurable**: nada fijado dentro del componente, cada zona de la fila solo
aparece o reacciona si quien la usa la configura (p. ej. el botón `Lanzar`
solo existe en la lista de Sesiones).

## Comportamiento acordado

- Zonas de la fila: **marcador**, **fila completa** (pulsar y mantener
  pulsada) y **zona derecha**. No hacen falta acciones separadas para título o
  subtítulo.
- La fila puede **desplegarse** con contenido contextual, que son otras
  `ListRow`.
- En la lista de Sesiones (lanzadera):
  - Pulsar la fila o `[>] Lanzar` → conecta.
  - Pulsar el marcador o mantener pulsada la fila → despliega o pliega las
    acciones contextuales.
  - Acciones: **Editar** (abre Configuración con la ficha de la sesión y, al
    cerrar el editor, vuelve a Sesiones), **Duplicar** (crea la copia y se
    queda en la lista) y **Eliminar** (pide confirmación).
- Solo una fila desplegada a la vez.
- En escritorio, el clic derecho equivale a mantener pulsado.

## Implementación

- `ui/Components.kt`: `ListRow` con todo opcional: `title` (único obligatorio),
  `subtitle`, `marker` + `markerColor`, `onClick`, `onLongClick` (también clic
  derecho, capturado en el pase inicial para que no llegue al clic),
  `onMarkerClick` (zona de toda la altura de la fila), `trailing`
  (`RowScope`) y `expanded` + `expandedContent` (`ColumnScope`, animación de
  despliegue vertical, sobre `surface` y sangrado `SpaceXl`). Nuevo
  `ConfirmRow`: pregunta con `[<] No` y un botón de confirmar configurables.
- `ui/SessionsArea.kt`: la lanzadera lleva el estado de la fila desplegada
  (una a la vez) y el de la confirmación de borrado; usa
  `ConfigController.duplicateSession` y `deleteSession`. Una sesión con el
  host roto no conecta, pero sus acciones siguen disponibles.
- `ui/AppShell.kt` + `ui/ConfigArea.kt`: "Editar" abre Configuración en la
  pestaña Sesiones con el editor de esa sesión; al cerrar el editor
  (`[<]`, Guardar o Eliminar) se vuelve a la lanzadera, también con pestañas
  abiertas. Abrir Configuración con `[*]` sigue igual.
- Usos que pasaban `onClick = {}` (Grupos, panel de túneles del terminal) ya
  no lo pasan y dejan de parecer pulsables. El resto de usos no cambia.

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:desktopApp:compileKotlin`, `:shared:desktopTest` y
  `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- Emulador `Pixel_9_Pro_XL` (build de debug), 2026-09-29: el marcador y la
  pulsación larga despliegan las acciones y solo queda una fila abierta;
  pulsar la fila conecta (pestaña "proyecto demo", conectada); Duplicar crea
  "(copia)" al final y pliega; Eliminar pide confirmación, "No" la retira y
  "Sí" borra; Editar abre la ficha, y `[<]` y Guardar vuelven a la lanzadera,
  también con una pestaña abierta. Las listas de Configuración se ven igual.
- **Sin verificar**: el clic derecho en la app de escritorio (el agente no
  puede hacer clic en la ventana).
- **Confirmado por el usuario (2026-09-29)** en el emulador; decisión visual aceptada. Guía de uso del componente en [[Componentes UI compartidos]]. Cerrada.
- Publicada en
  [`v0.1.0-beta.6`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.6)
  (2026-09-29); detalle de la comprobación del Release en
  [[Seguimiento de tareas pendientes]]. 👤 Falta que el usuario instale la APK
  en el Pixel desde el Release y confirme que funciona.
