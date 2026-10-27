---
Nombre: 'Colores en las sesiones del agente'
Estado: Hecha
Resumen: 'En las sesiones que corren en el agente (nivel 3) la shell arrancaba sin `TERM`, así que Claude Code y otras apps que comprueban el terminal quitaban todo el color: el muñeco salía blanco y en `claude agents` no se veía la línea seleccionada. El agente pasa a dar a la shell de cada sesión `TERM=xterm-256color` y `COLORTERM=truecolor`.'
Decisiones: 'El agente siempre fija `TERM`/`COLORTERM` para el emulador de la app, sin mirar lo que tenga su propio entorno (el demonio arranca por `exec` de SSH, sin PTY). Ajuste de [[Resiliencia nivel 3 agente propio en el destino]].'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T20:32:08+01:00
Última modificación: 2026-10-27T20:57:21+01:00
---

# Colores en las sesiones del agente

## Objetivo

El usuario usa Claude Code por SSH desde el Pixel, igual que cuando apareció
[[Rueda del ratón para apps a pantalla completa en el terminal]]. El muñeco de
Claude Code salía blanco en vez de naranja y en `claude agents` no se veía la
línea seleccionada, que normalmente va resaltada con un fondo de color. El
saludo de fastfetch sí salía con colores.

Diagnóstico:

- Grabando lo que emite Claude Code en un PTY con `TERM=xterm-256color` y
  pasándolo por `TerminalEmulator`, los colores llegan bien (el muñeco en
  `91`, con fondo `40`): el emulador no es el problema.
- En la sesión real del Pixel (agente canary en el PC de desarrollo), el
  entorno de `fish -il` y de `claude` bajo el `--session-host` **no tiene
  `TERM`**. El demonio del agente se arranca con un `exec` de SSH sin PTY,
  así que tampoco lo tiene, y `NewPty` lo heredaba tal cual.
- Sin `TERM`, Claude Code (como cualquier programa que use supports-color)
  decide que la terminal no admite color. fastfetch pinta igualmente porque
  no lo comprueba.
- La conexión directa (sin agente) pide el PTY a sshd como `xterm-256color`
  (`SshjConnector`), así que solo fallaban las sesiones del agente.

## Criterios de finalización

- La shell de cada sesión del agente en Unix recibe `TERM=xterm-256color` y
  `COLORTERM=truecolor`, sustituyendo los valores que tuviera el demonio.
- Test del agente que lo comprueba.
- Probado desde Android con una sesión de nivel 3 (el usuario, en la canary
  del Pixel): Claude Code sale con colores.

## Verificación

- `TestNewPtySetsTerminalEnv` (la shell de `NewPty` ve `TERM=xterm-256color`
  y `COLORTERM=truecolor` aunque el demonio tenga otro `TERM`): falla sin el
  arreglo y pasa con él. `go test ./...` del agente en verde.
- El usuario lo confirmó desde el Pixel con la canary (sesión de nivel 3
  nueva tras actualizarse el agente canary): Claude Code sale con colores y
  en `claude agents` se ve resaltada la línea seleccionada.

## Resultado

- `NewPty` (Unix) arranca la shell con el entorno del demonio más
  `TERM=xterm-256color` y `COLORTERM=truecolor` (`terminalEnv`), sustituyendo
  los que tuviera. Windows no cambia (ConPTY no usa `TERM`).
- No había ninguna decisión detrás: ADR-0008 y ADR-0009 solo fijan
  `$SHELL -il`, y el entorno se heredaba sin más desde la primera versión del
  agente.
