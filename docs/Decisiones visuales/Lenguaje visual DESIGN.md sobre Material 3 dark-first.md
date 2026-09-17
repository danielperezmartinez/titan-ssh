---
Nombre: Lenguaje visual con formato DESIGN.md sobre Material 3, dark-first
Estado: Aceptada
Ámbito: Tokens
Resumen: El lenguaje visual de titan-ssh se documenta con el formato DESIGN.md (de la colección awesome-design-md) y se materializa como tokens de Material 3 en Compose. Tema dark-first (claro opcional más adelante). Los tokens concretos (paleta, tipografía, ANSI) se decidirán tras elegir una referencia.
Decisión: Adoptar el formato DESIGN.md como fuente del lenguaje visual, mapearlo a tokens de Material 3 en Compose y partir de un tema dark-first.
Consecuencias: Se descartan las otras dos herramientas de diseño evaluadas; hace falta traducir los tokens del DESIGN.md a Material 3 y definir aparte la estética específica de terminal.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T17:14:02+02:00
Última modificación: 2026-09-17T17:14:02+02:00
---

# Lenguaje visual con formato DESIGN.md sobre Material 3, dark-first

## Contexto

titan-ssh no debe verse genérico ni imitar a Termius. Se evaluaron tres
herramientas de diseño (todas orientadas a web); se conserva solo
[awesome-design-md](https://github.com/voltagent/awesome-design-md), una
colección de ficheros `DESIGN.md` (sistemas de diseño en markdown: tema, roles de
color, tipografía, espaciado, componentes, elevación, do's/don'ts). Su formato es
agnóstico de plataforma, así que sirve como fuente del lenguaje visual aunque
generemos Compose y no web.

## Decisión

- **Formato `DESIGN.md`** como columna vertebral del lenguaje visual de titan-ssh.
- **Mapear los tokens a Material 3** en Compose Multiplatform (color scheme,
  tipografía, shapes, elevación).
- **Dark-first**: el tema oscuro es el principal (natural en una herramienta de
  terminal); el tema claro es opcional y posterior.
- La **estética específica de terminal** (fuente monospace, paleta ANSI,
  contraste, cursor/selección) y la ergonomía táctil/teclado de Android se definen
  aparte, ya que la colección no las cubre.

## Alternativas consideradas

- **Vercel web-design-guidelines** — útil solo como checklist de principios
  agnósticos (focus, zonas táctiles, contraste, reduced-motion, estados de
  carga/vacío/error); no como herramienta (revisa código web, no Compose).
- **image-to-code-skill** — flujo "imagen → código web" orientado a landing
  pages; no encaja con una app funcional con terminal. Descartada.

## Consecuencias

- Positivas: un formato probado y portable para documentar el lenguaje visual;
  buenas referencias de herramientas para desarrolladores (Warp, Raycast, Vercel,
  Cursor…) afines a un cliente SSH.
- Negativas / compromisos: los `preview.html` son web (los tokens se traducen a
  Material 3); falta definir los tokens concretos tras elegir una referencia, en
  una decisión visual posterior de Ámbito Tokens.
