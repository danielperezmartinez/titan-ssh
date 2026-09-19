---
Nombre: Resiliencia nivel 2 auto-tmux o screen
Estado: Pendiente
Resumen: 'Nivel 2 del modelo de resiliencia (ADR-0003): cuando el destino tenga tmux o screen, envolver la sesión (p. ej. `tmux new -A -s titan-<sesión>`) para que el proceso en primer plano sobreviva a la caída y al reconectar se re-enganche a la misma sesión del multiplexor, no a un shell nuevo. Sin instalación adicional si ya están presentes. Mejora opcional sobre el nivel 1 (reconexión de cliente), ya entregado.'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]]. Se apoya en el nivel 1 de [[Resiliencia de sesión ante microcortes de red]] y en [[Motor de conexión SSH]].
Bloqueada: []
Fecha de creación: 2026-09-19T16:45:00+02:00
Última modificación: 2026-09-19T16:45:00+02:00
---

# Resiliencia nivel 2: auto-tmux/screen

## Objetivo

Sobre la reconexión de cliente del nivel 1 (que ya evita cerrar la pestaña y
conserva el scrollback, pero abre un shell nuevo tras una caída total y por tanto
pierde el proceso en primer plano remoto), añadir persistencia del proceso remoto
**cuando el destino disponga de un multiplexor de terminal** (tmux o screen).

## Criterios de finalización (borrador)

- Detectar si el destino tiene tmux/screen disponible.
- Si lo hay (y la sesión lo pide), envolver el shell en una sesión con nombre
  estable por sesión (p. ej. `tmux new -A -s titan-<id>`), de modo que el proceso
  sobreviva a la caída del transporte.
- Al reconectar (nivel 1), re-enganchar a esa sesión del multiplexor en vez de
  abrir un shell limpio, recuperando el proceso en primer plano.
- Configurable por sesión; degradar limpiamente al nivel 1 si el multiplexor no
  está.

## Notas

- Interacción con `ResilienceLevel.AUTO_MULTIPLEXER` del modelo de config y con la
  automatización de arranque/reconexión ([[ScriptRunner]]).
- Detalle de implementación por afinar al construirlo.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
