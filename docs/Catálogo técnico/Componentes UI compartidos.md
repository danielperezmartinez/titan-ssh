---
Nombre: "Componentes UI compartidos"
Tipo: "Componente UI"
Área: "UI compartida"
Feature: "Shared UI"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/Components.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Biblioteca de primitivas Compose dark-first, reutilizables en cualquier pantalla: TitanTextField, TitanDropdown (genérico), TitanButton (ButtonKind PRIMARY/SECONDARY/DANGER), TitanCheck, TitanSegmented (genérico), ListRow, EmptyState, GlyphButton, Hairline, SectionHeader, Caption, EditorScaffold y bodyPadding(). Construidas al lenguaje visual (mono, marcadores ASCII, superficies planas con hairline 1px, radios 4px/0px), evitando el chrome de Material (elevación, tarjetas redondeadas, labels animados) que competiría con la identidad de terminal. Se apoyan en los tokens de TitanColors/TitanDimens."
Última modificación: 2026-09-24T12:00:00+02:00
---

# Componentes UI compartidos

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[Components.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/Components.kt);
cada composable lleva su KDoc con el contrato de uso.

Cuándo reutilizar en lugar de crear:

- Campos, selectores, botones, toggles y filas de lista de la app se construyen
  con estas primitivas; no se introduce chrome de Material que rompa la estética.
- `EditorScaffold` es la envoltura estándar de cualquier editor (barra `[<]` +
  título + `[x] Eliminar` opcional + `[ok] Guardar` sobre cuerpo desplazable).
- Los genéricos `TitanDropdown<T>` / `TitanSegmented<T>` sirven enums, hosts, etc.

Consumen [[Tema y tokens visuales]]. Los editores y áreas concretas del panel
(host, sesión, snippet, grupos) se construyen encima; ver
[[Panel de gestión de hosts y sesiones]]. Regidos por
[[Vocabulario ASCII ampliado y disciplina de color]].
