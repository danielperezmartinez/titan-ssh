---
Nombre: "Editores de script y túnel como pantalla propia"
Estado: Pendiente
Resumen: 'Refactor UX del editor de sesión: hoy "Añadir script" y "Añadir túnel" despliegan un formulario inline (ScriptCard/TunnelCard expandibles) metido a calzador en el editor de sesión. En su lugar, cada uno debe tener su propia pantalla de edición dedicada (como el editor de host o de grupo), a la que se navega y se vuelve, reutilizada para crear y editar. El editor de sesión solo lista y enlaza.'
Decisiones: Ajuste de [[Panel de gestión de hosts y sesiones]] y [[Scripts de inicio por sesión]]; reutiliza [[Componentes UI compartidos]] (`EditorScaffold`) y sigue [[Vocabulario ASCII ampliado y disciplina de color]].
Bloqueada: []
Fecha de creación: 2026-09-18T17:15:00+02:00
Última modificación: 2026-09-18T17:15:00+02:00
---

# Editores de script y túnel como pantalla propia

## Objetivo

Sacar la edición de scripts y de túneles del editor de sesión a **pantallas de
edición dedicadas y reutilizables**, en lugar de los formularios inline que hoy se
despliegan dentro del propio editor de sesión (`ScriptCard` / `TunnelCard`
expandibles), que quedan "a calzador". El patrón a seguir es el mismo que ya se usa
para hosts y grupos referenciados desde la sesión: navegas a una pantalla propia,
editas y vuelves.

## Contexto actual

En `shared/src/commonMain/kotlin/im/gar/titanssh/ui/SessionEditor.kt`:

- `[+] Añadir script` añade un `SessionScript` y se edita en una `ScriptCard`
  inline expandible (fase, comportamiento, expect, reconnect, snippet, secretos…).
- `[+] Añadir túnel` hace lo mismo con `TunnelCard` (tipo, puertos, destino…).

Todo el formulario vive embebido en el editor de sesión, que queda sobrecargado.

## Criterios de finalización

- **`ScriptEditor`** y **`TunnelEditor`** como pantallas propias sobre
  `EditorScaffold` (barra `[<]` + título + `[x] Eliminar` + `[ok] Guardar`),
  reutilizadas para crear y para editar, con todos los atributos v1 actuales
  (nada de regresión funcional respecto a las cards).
- El editor de sesión pasa a **listar** scripts y túneles (fila + resumen) y a
  **navegar** a su editor; `[+] Añadir` abre el editor en modo creación.
- Se conserva el reordenado de scripts (hoy por índice) y el comportamiento de
  guardado/persistencia; sin regresiones en `ConfigModelTest`/`JsonFileConfigStoreTest`.
- Coherente con el lenguaje visual; sin chrome de Material que rompa la estética.

## Notas de diseño

- "Reutilizar igual que hosts y grupos" se refiere al **patrón de navegación**
  (pantalla propia reutilizable para alta/edición), no a convertir cada script en
  una entidad global de biblioteca: la biblioteca reutilizable global ya son los
  **snippets** ([[Panel de gestión de hosts y sesiones]]).
- Revisar la máquina de navegación del editor de sesión (cómo entra/vuelve) para
  encajar los sub-editores sin perder el estado en edición.

## Verificación

<Se rellena al completar: build de ambos targets, tests sin regresión y
comprobación real del flujo en dispositivo/escritorio.>

## Resultado

<Se rellena al completar.>
