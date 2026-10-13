---
Nombre: 'Barra de teclas configurable por arrastre'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'La barra de encima del teclado es la misma en la terminal y en el mouse pad: las teclas del usuario se desplazan de lado y [*] (abre su editor) y [kbd] quedan fijos a la derecha. El editor, Barra de teclas, pone arriba la barra en varias líneas y abajo el catálogo por grupos; se mantiene pulsada una tecla y se arrastra para añadirla, moverla o quitarla, y un toque en el catálogo la añade al final. Las teclas que ya están en la barra salen atenuadas en el catálogo; las que solo tienen sentido en un escritorio remoto salen atenuadas en la terminal.'
Decisión: 'Un solo componente de barra con [*] junto a [kbd] y un editor de arrastre con la barra arriba y el catálogo abajo, guardando cada cambio al momento.'
Consecuencias: 'Arrastrar empieza tras una pulsación larga, para no chocar con el scroll de la barra ni del catálogo. Una tecla no puede estar dos veces. La barra no tiene un aspecto distinto en la terminal y en el mouse pad: lo que no aplica se atenúa, no se esconde. Restablecer vuelve a la barra por defecto.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-13T08:30:00+02:00
Última modificación: 2026-10-13T08:30:00+02:00
---

# Barra de teclas configurable por arrastre

## Contexto

La barra de teclas accesorias estaba fija en el código y había dos: la de la
terminal y la del mouse pad, con teclas distintas. El usuario quiere elegir sus
teclas y su orden arrastrándolas, y que la barra sea una sola en toda la app.
El diseño se acordó con un boceto ASCII en la sesión de la tarea
[[Barra de teclas configurable con arrastre]].

## Decisión

- **Barra** (`KeyBarRow`): las teclas del usuario con scroll lateral (como las
  barras de pestañas); a la derecha, fuera del scroll, `[*]` y `[kbd]`. `[*]`
  es el "engranaje" ASCII de [[Vocabulario ASCII ampliado y disciplina de color]].
  Un modificador pegajoso activo va en `accent`; una tecla que no aplica
  (Win, ImpPnt, Menú, multimedia en la terminal) va en `stone` y no responde.
- **Editor** (`KeyBarEditor`), pantalla propia con `[<]`, título y
  `Restablecer`:
  - Arriba, fija, "TU BARRA": la barra en varias líneas (salta de línea, como
    los grupos de opciones), para ver todas las teclas mientras se ordena.
  - Abajo, con scroll, "DISPONIBLES": el catálogo por grupos (Modificadores,
    Control, Navegación, Atajos, Funciones, Símbolos, Escritorio remoto,
    Acciones). Las que ya están en la barra, atenuadas e inertes.
  - Mantener pulsada una tecla la levanta (vibración); sigue al dedo con
    hairline `accent`. Sobre la barra, la zona se tiñe de `accent` y una línea
    `accent` marca dónde caerá. Si viene de la barra y sale de ella, el
    catálogo se tiñe de `danger` y dice "Suelta aquí para quitarla de la barra".
  - Un toque en una tecla del catálogo la añade al final.
  - Cada cambio se guarda en el momento; no hay botón de guardar.

## Alternativas consideradas

- Barra del editor en una sola línea con scroll, igual que sobre el teclado —
  descartada: en un móvil no se ve entera y arrastrar a una posición oculta
  exige desplazar mientras se arrastra.
- Una barra distinta por tipo de sesión (terminal y mouse pad) — descartada por
  el usuario: quiere la misma barra en los dos sitios.
- Ocultar en la terminal las teclas de escritorio — descartada: la barra
  cambiaría de forma entre pestañas; atenuarlas mantiene el orden.

## Consecuencias

- Positivas: una sola barra que el usuario controla; el arrastre y el toque
  cubren tanto a quien prefiere un gesto como a quien no.
- Negativas / compromisos: el arrastre necesita pulsación larga, menos
  descubrible; los textos de la pantalla lo explican.
