---
Nombre: Tokens visuales de titan-ssh (dark-first, base opencode)
Estado: Aceptada
Ámbito: Tokens
Resumen: Set de tokens dark-first inspirado en opencode (mono en todo, marcadores ASCII como iconos, radios 4px/0px, plano con hairlines, rampa semántica Apple), adaptado a Material 3 y ampliado con una paleta ANSI para el terminal. Fuente OSS JetBrains Mono en lugar de Berkeley Mono.
Decisión: Adoptar estos tokens (color, tipografía mono, espaciado, radios, elevación plana, iconografía ASCII y paleta ANSI) como base visual dark-first de titan-ssh.
Consecuencias: Identidad mono-terminal muy marcada; los tokens se mapean a Material 3 en Compose; el tema claro es posterior.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T17:22:40+02:00
Última modificación: 2026-09-17T17:28:32+02:00
---

# Tokens visuales de titan-ssh (dark-first, base opencode)

Enmarcado en [[Lenguaje visual DESIGN.md sobre Material 3 dark-first]]. Norte
visual: opencode (https://getdesign.md/opencode.ai/design-md). opencode es en
origen claro con una única tarjeta oscura (su TUI); aquí se **invierte a
dark-first** conservando su ADN.

## ADN heredado de opencode

- **Mono en todo** (UI y terminal): la decisión de una sola familia monoespaciada
  es la identidad.
- **Marcadores ASCII** `[+]` `[-]` `[x]` como iconografía (no SVG).
- **Radios**: 4px en elementos interactivos, 0px en contenedores.
- **Plano**: sin sombras ni gradientes; jerarquía por color de superficie y
  hairlines de 1px.
- **Rampa semántica Apple** reservada a estados (terminal, sesión), no a la UI
  decorativa.

## Color (dark-first)

Superficies (de fondo a elevado):

- `canvas` = `#201d1d` (fondo principal, near-black cálido de opencode)
- `surface` = `#302c2c` (paneles, filas de input, tarjetas)
- `surface-elevated` = `#3a3636` (elevación mayor)
- `terminal-bg` = `#161313` (pozo del terminal, más profundo que el canvas)

Texto (sobre oscuro):

- `ink` = `#fdfcfc` (texto primario, crema de opencode)
- `body` = `#d8d5d5`
- `mute` = `#9a9898` (secundario)
- `stone` = `#6e6e73` (mínima énfasis / deshabilitado)

Líneas:

- `hairline` = `rgba(253,252,252,0.12)` (divisor 1px)
- `hairline-strong` = `#646262`

Semántica (variantes dark-mode de Apple HIG, por legibilidad sobre oscuro):

- `accent` (info/enlaces en producto) = `#0a84ff`
- `success` = `#30d158`
- `warning` = `#ff9f0a`
- `danger` = `#ff453a`

## Paleta ANSI del terminal (definida por nosotros)

Fondo `#161313` · texto `#d8d5d5` · cursor `#0a84ff` · selección
`rgba(10,132,255,0.30)`.

Normal: black `#201d1d` · red `#ff453a` · green `#30d158` · yellow `#ff9f0a` ·
blue `#0a84ff` · magenta `#bf5af2` · cyan `#5ac8fa` · white `#d8d5d5`.

Bright: black `#6e6e73` · red `#ff6961` · green `#66d97e` · yellow `#ffb340` ·
blue `#409cff` · magenta `#da8fff` · cyan `#8fe0ff` · white `#fdfcfc`.

## Tipografía

- **Familia: JetBrains Mono** (pesos 400/500/700), sustituto OSS de Berkeley Mono
  recomendado por el propio DESIGN.md de opencode. Fallback: IBM Plex Mono →
  ui-monospace → SFMono → Menlo → Consolas.
- Escala (ajustada a densidad de app, no de landing):
  - `display` 28/700 (branding, estados vacíos)
  - `heading` 16/700 (etiquetas de sección)
  - `body` 14/400 · `body-strong` 14/500
  - `caption` 12/400 (metadatos, pie)
  - `button` 14/500
  - `terminal` 13 (tamaño configurable por el usuario), interlineado ~1.4

## Espaciado, radios y elevación

- Base 8px con pasos finos: `xxs` 1 · `xs` 4 · `sm` 8 · `md` 12 · `lg` 16 ·
  `xl` 24 · `xxl` 32 · `section` 48 (reducido frente a los 96px de landing de
  opencode, por ser una app).
- Radios: `none` 0 · `sm` 4 · `full` 9999 (solo avatares/estados circulares).
- Elevación: **sin sombras**; niveles por color de superficie + hairline.

## Iconografía ASCII → estados de titan-ssh

Reusa los marcadores como lenguaje de estado (con celda táctil de 44px en
Android):

- `[+]` conectado / activo · `[-]` reconectando / en pausa · `[x]` cerrado /
  error · `[>]` ejecutando.
- `[+]` / `[-]` también para expandir/plegar (filas tipo FAQ) y añadir.

## Do's & Don'ts (dark-first)

- **Do:** todo en JetBrains Mono; brackets ASCII como iconos; plano con hairlines;
  4px interactivo / 0px contenedor; semántica solo para estados; `canvas` oscuro
  como fondo único.
- **Don't:** introducir una sans de UI o display; sombras/gradientes; iconos SVG
  en lugar de brackets; usar la rampa semántica en CTAs decorativos.

## Criterios confirmados con el usuario

1. **Mono en todo** con JetBrains Mono (fiel a opencode). Confirmado.
2. **Variantes dark-mode de Apple** para la semántica. Confirmado.
3. `section` reducido a 48px (densidad de app). Confirmado.
4. Paleta **ANSI** propuesta (definida por nosotros). Confirmada.

Vista previa de referencia: artifact "titan-ssh dark theme".
