---
Nombre: 'Menú de la pestaña con pulsación larga'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'Cada pestaña de la barra tiene un menú de acciones que se abre con una pulsación larga (en escritorio también con clic derecho). Si el dedo se levanta, el menú queda abierto; si en vez de levantarlo se arrastra más allá del margen táctil, el menú se cierra y la pestaña se mueve como antes. Ofrece Renombrar [~], Abrir en paralelo [+] y Abrir explorador de archivos [d]. Renombrar abre una franja bajo la barra con el campo, [ok] y [x].'
Decisión: 'Pulsación larga en la pestaña = menú de acciones (DropdownMenu anclado a la pestaña); un arrastre tras ella lo cierra y reordena; renombrar se edita en una franja bajo la barra de pestañas.'
Consecuencias: 'No hace falta un botón ⋮ en cada pestaña; reordenar sigue siendo pulsación larga + arrastre en un solo gesto. Un temblor del dedo no cancela el menú (margen táctil). Las acciones nuevas de una pestaña van en este menú.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-14T13:00:00+02:00
Última modificación: 2026-10-14T13:00:00+02:00
---

# Menú de la pestaña con pulsación larga

## Contexto

[[Renombrar las pestañas y las sesiones activas]] y
[[Abrir el explorador SFTP desde una sesión abierta]] necesitaban acciones
sobre una pestaña abierta, y hasta ahora la pulsación larga de la pestaña solo
servía para reordenarla. El usuario pidió el comportamiento habitual de otras
apps: pulsación larga abre el menú; si después se arrastra, el menú se cierra
y la pestaña se mueve.

## Decisión

- **Abrir**: pulsación larga en la pestaña (vibración corta en Android); en
  escritorio también el clic derecho. La pestaña se marca con el borde
  `accent` mientras su menú está abierto.
- **Soltar sin moverse**: el menú queda abierto y se elige una opción; tocar
  fuera lo cierra.
- **Arrastrar**: cuando el dedo se mueve más del margen táctil del sistema
  después de la pulsación larga, el menú se cierra y empieza el arrastre de
  reordenación de siempre, en el mismo gesto.
- **Acciones**, cada una con su marcador ASCII:
  - `[~]` Renombrar: franja bajo la barra de pestañas con el campo (enfocado,
    con el nombre por defecto como texto de ayuda), `[ok]` y `[x]`, y una
    línea que dice dónde se guarda el nombre (el agente del destino o solo la
    pestaña). Un error del agente sustituye a esa línea, en `danger`.
  - `[+]` Abrir en paralelo (en `accent`, como añadir): solo en sesiones que
    admiten instancias (ADR-0020).
  - `[d]` Abrir explorador de archivos: solo en pestañas de terminal.
- El panel del agente reutiliza el mismo editor de nombre como fila
  desplegada `[~] Renombrar` junto a Terminar.

## Alternativas consideradas

- Botón ⋮ en cada pestaña — más descubrible pero ocupa sitio en cada
  pestaña; quedó como alternativa por si la pulsación larga no fuera viable
  (lo fue: comprobado en el emulador).
- Pulsación larga = menú y reordenar desde el menú ("mover a izquierda /
  derecha") — más torpe que arrastrar.
- Diálogo modal para renombrar — tapa la terminal y choca con el teclado; la
  franja es más ligera.

## Consecuencias

- Positivas: un solo gesto para menú y reordenación; la barra no gana botones.
- Negativas / compromisos: el menú es poco descubrible para quien no sepa de
  la pulsación larga; el clic izquierdo mantenido en escritorio sigue
  funcionando igual que en el móvil.
