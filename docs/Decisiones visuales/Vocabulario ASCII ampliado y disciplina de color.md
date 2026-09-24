---
Nombre: Vocabulario ASCII ampliado y disciplina de color semántico
Estado: Aceptada
Ámbito: Componente
Resumen: 'Amplía (no reemplaza) el set de marcadores ASCII de los tokens base con los glifos de navegación/estructura que pide la UI real (reordenar, desplegar, grupo, atrás, confirmar), todos ASCII, y fija la disciplina de color: accent = interacción/selección/enlaces (rol de producto); success/warning/danger = SOLO estados reales (conexión/sesión/terminal), con danger también para acciones destructivas. Nada de rampa semántica como adorno.'
Decisión: Adoptar los marcadores ASCII adicionales [^] [v] [#] [<] [ok] junto a los base [+] [-] [x] [>], y aplicar accent solo a interacción y la rampa semántica solo a estados (y danger a lo destructivo).
Consecuencias: La UI deja de pintar filas/CTAs con colores de estado; el verde/ámbar/rojo quedan libres para señalar estado real de conexión, lo que los hace informativos; un único accent unifica la interacción.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-18T14:36:16+02:00
Última modificación: 2026-09-24T14:00:00+02:00
---

# Vocabulario ASCII ampliado y disciplina de color semántico

Complementa —**no reemplaza**— a
[[Tokens visuales dark-first base opencode]]. Nace al construir la primera UI real
([[Panel de gestión de hosts y sesiones]]): el set de marcadores base se quedó
corto para navegación/estructura, y una primera pasada usó la rampa semántica de
forma decorativa (todas las filas en verde, botones primarios rellenos de accent),
que contradice el Don't *"semántica solo para estados"*.

## Marcadores ASCII

Set base (de los tokens): `[+]` conectado/activo · `[-]` reconectando/pausa ·
`[x]` cerrado/error · `[>]` ejecutando; y `[+]`/`[-]` para expandir/plegar y
añadir.

Ampliación (todos ASCII, sin glifos Unicode como `✓`):

- `[^]` / `[v]` — reordenar (subir/bajar) y, `[v]`, indicar un desplegable.
- `[#]` — grupo / etiqueta (proyecto).
- `[<]` — volver / atrás en un editor.
- `[ok]` — confirmar / guardar (sustituye al `[✓]` no-ASCII que se coló).
- `[ ]` / `[x]` — casilla desmarcada / marcada (toggle de formulario).
- `[i]` — información / Acerca de (acción de la cabecera de la app; en
  `accent` mientras la pantalla está abierta). Añadido el 2026-09-24.
- `[=]` — documento de texto que se abre para leer (licencia, avisos).
  Añadido el 2026-09-24.

Regla: los marcadores son **ASCII imprimible dentro de brackets**; si hace falta
uno nuevo, se añade aquí antes de usarlo, no ad hoc.

## Disciplina de color

- **`accent`** (`#0a84ff`) = **interacción**: selección (opción de un segmentado,
  ítem elegido de un desplegable, toggle marcado), foco/cursor, enlaces y
  afordancias de acción tipo enlace ("añadir"), y el hairline que distingue el
  botón primario. Es el rol "info/enlaces en producto" de los tokens.
- **`success` / `warning` / `danger`** = **solo estados reales** de conexión,
  sesión o terminal (p. ej. `[+]` verde = conectado en una pestaña viva). Excepción
  pragmática: `danger` también viste las **acciones destructivas** (eliminar) por
  convención.
- **Prohibido**: teñir filas de lista, iconos de entradas de configuración o CTAs
  con la rampa semántica como decoración. Una entrada guardada en Configuración va
  en color neutro (`body`/`mute`); no está "conectada".
- **Botones**: primario = superficie elevada + hairline `accent` + texto `ink`
  (no relleno semántico); secundario = hairline neutro + texto `body`; destructivo
  = hairline/texto `danger`.

## Aplicación

Aplicado en la UI del panel: marcadores de host/sesión/snippet/grupo en neutro,
`danger` reservado a error real (host colgado) y a eliminar, `accent` a selección
e interacción, y `[ok]` en Guardar. El verde/ámbar/rojo quedan disponibles para
cuando el terminal muestre estado de conexión vivo
([[Terminal multipestaña con sesiones simultáneas]],
[[Resiliencia de sesión ante microcortes de red]]).

## Nota sobre tipografía

En la misma tanda se dejó de usar el monospace del sistema: **JetBrains Mono** se
**bundlea** como recurso de Compose (pesos 400/500/700, OFL-1.1 en
`third_party/JetBrainsMono/`), cumpliendo "Mono en todo" con la familia acordada
en los tokens. Esto cierra el pendiente que arrastraba el esqueleto.
