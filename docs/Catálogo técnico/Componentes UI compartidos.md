---
Nombre: "Componentes UI compartidos"
Tipo: "Componente UI"
Área: "UI compartida"
Feature: "Shared UI"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/Components.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Biblioteca de primitivas Compose dark-first, reutilizables en cualquier pantalla: TitanTextField, TitanDropdown (genérico), TitanButton (ButtonKind PRIMARY/SECONDARY/DANGER), TitanCheck, TitanSegmented (genérico; sus opciones saltan de línea si no caben), ListRow (fila configurable por zonas: fila con pulsar y mantener pulsada, marcador y zona derecha, cada una solo si se le da acción; puede desplegar debajo acciones contextuales hechas con más ListRow y llevar una tercera línea opcional, note, en su propio color, p. ej. un estado en warning), ConfirmRow (confirmación en línea; sus botones bajan bajo la pregunta si no cabe en una línea a su lado), StatusDetailPanel (panel que despliega bajo la franja de una pestaña el detalle técnico de su estado, seleccionable para copiarlo), EmptyState, GlyphButton, Hairline, SectionHeader, Caption, EditorScaffold (su botón de borrar siempre pide confirmación en la propia barra) y bodyPadding(). Construidas al lenguaje visual (mono, marcadores ASCII, superficies planas con hairline 1px, radios 4px/0px), evitando el chrome de Material (elevación, tarjetas redondeadas, labels animados) que competiría con la identidad de terminal. Se apoyan en los tokens de TitanColors/TitanDimens."
Última modificación: 2026-10-03T08:46:47+02:00
---

# Componentes UI compartidos

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[Components.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/Components.kt);
cada composable lleva su KDoc con el contrato de uso.

Cuándo reutilizar en lugar de crear:

- Campos, selectores, botones, toggles y filas de lista de la app se construyen
  con estas primitivas; no se introduce chrome de Material que rompa la estética.
- `EditorScaffold` es la envoltura estándar de cualquier editor (barra `[<]` +
  título + borrar opcional + `[ok] Guardar` sobre cuerpo desplazable). El botón
  de borrar dice `[x] Eliminar` salvo que se pase otro `deleteLabel` (p. ej.
  `[x] Quitar` cuando solo se saca algo de la ficha, sin borrarlo). Nunca
  borra con un toque: cambia la barra por un `ConfirmRow` con
  `deleteQuestion` (por defecto "¿Eliminar?"), un `deleteSubtitle` opcional
  para avisar de las consecuencias y `deleteConfirmLabel` (por defecto
  `[x] Sí`). `onDelete` solo se llama al confirmar.
- Los genéricos `TitanDropdown<T>` / `TitanSegmented<T>` sirven enums, hosts, etc.
- `ListRow` es **la** fila de cualquier lista de la app (listas de
  Configuración, lanzadera, editores, menús del terminal, Acerca de). No se
  crea otra fila a mano: si falta algo, se amplía `ListRow` con un parámetro
  opcional que por defecto no cambie nada. Detalle abajo.

## Cómo usar `ListRow`

Todo es opcional salvo `title`. Una zona solo aparece o reacciona si se
configura, y el componente no decide qué gesto hace qué: lo decide quien la usa.

| Parámetro | Qué hace | Si no se pasa |
|---|---|---|
| `title`, `subtitle` | Texto principal y línea secundaria en `mute`. | Sin subtítulo. |
| `marker`, `markerColor` | Glifo ASCII a la izquierda (vocabulario en [[Vocabulario ASCII ampliado y disciplina de color]]). | Sin marcador; el texto arranca a la izquierda. |
| `onClick` | Pulsar la fila. | La fila no es pulsable. |
| `onLongClick` | Mantener pulsada la fila; en escritorio, también el clic derecho. | Sin pulsación larga. |
| `onMarkerClick` | Pulsar el marcador; la zona táctil es toda la altura de la fila. | El marcador forma parte de la fila (recibe `onClick`). |
| `trailing` | Contenido libre a la derecha (`RowScope`): un botón, glifos… Cada elemento lleva su propia acción. | Nada a la derecha. |
| `expanded`, `expandedContent` | Contenido bajo la fila (`ColumnScope`), con animación, sobre `surface` y sangrado. Normalmente más `ListRow` como acciones contextuales. | No se despliega nada. |

Reglas de uso:

- **El estado desplegado lo lleva la lista**, no la fila. Para "una sola fila
  abierta", la lista guarda el id abierto (`var expandedId by remember {
  mutableStateOf<String?>(null) }`) y pasa `expanded = expandedId == item.id`.
- La lista decide qué gesto despliega (en la lanzadera, `onMarkerClick` y
  `onLongClick`). Mientras está desplegada, el marcador va en `accent`, salvo
  que muestre un error real.
- El separador entre filas (`Hairline()`) lo pone la lista, no la fila.
- Lo destructivo no se ejecuta con un toque: se sustituye la fila de la acción
  por un `ConfirmRow` (pregunta + `[<] No` + botón de confirmar, textos y tipo
  de botón configurables). Los botones van a la derecha de la pregunta si la
  pregunta y el subtítulo caben enteros en una línea a su lado; si no, bajan
  debajo, alineados a la derecha, para que la pregunta no se estreche.
- Colores: `danger` solo para errores reales y acciones destructivas; nunca
  como adorno.

Ejemplo (lanzadera de Sesiones, `SessionsArea.kt`, función `Launcher`):

```kotlin
ListRow(
    title = session.name,
    subtitle = "user@host:22",
    marker = "[>]",
    markerColor = if (expanded) TitanColors.Accent else TitanColors.Body,
    onClick = { onLaunch(resolved) },
    onLongClick = toggle,
    onMarkerClick = toggle,
    trailing = { TitanButton("[>] Lanzar", onClick = { onLaunch(resolved) }) },
    expanded = expanded,
    expandedContent = {
        ListRow(title = "Editar", marker = "[~]", onClick = { onEdit(session.id) })
        ListRow(title = "Duplicar", marker = "[+]", onClick = { /* … */ })
        if (confirmingDelete) {
            ConfirmRow(
                question = "¿Eliminar la sesión?",
                confirmLabel = "[x] Sí",
                onConfirm = { /* borrar */ },
                onCancel = { confirmingDelete = false },
            )
        } else {
            ListRow(
                title = "Eliminar",
                marker = "[x]",
                markerColor = TitanColors.Danger,
                onClick = { confirmingDelete = true },
            )
        }
    },
)
```

Una fila informativa solo lleva texto y marcador (p. ej. el panel de túneles
del terminal): sin `onClick` no parece pulsable. El aspecto acordado está en
[[Filas de lista con acciones contextuales desplegables]].

## Relaciones

Consumen [[Tema y tokens visuales]]. Los editores y áreas concretas del panel
(host, sesión, script de biblioteca, grupos) se construyen encima; ver
[[Panel de gestión de hosts y sesiones]]. Regidos por
[[Vocabulario ASCII ampliado y disciplina de color]].
