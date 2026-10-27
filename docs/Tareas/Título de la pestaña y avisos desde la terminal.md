---
Nombre: 'Título de la pestaña y avisos desde la terminal'
Estado: 'Planificando'
Resumen: 'El emulador de la app ignora el título que pone un programa (OSC 0/2), el BEL y las secuencias de notificación (OSC 9 y similares); solo usa OSC 7. Usarlos en el núcleo de la terminal, para cualquier programa: título como nombre de la pestaña y avisos cuando la pestaña no está a la vista. Independiente de los plugins.'
Decisiones: ''
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T22:20:00+01:00
Última modificación: 2026-10-27T22:20:00+01:00
---

# Título de la pestaña y avisos desde la terminal

## Objetivo

Que la terminal reaccione a lo que cualquier programa ya le dice: el
título de ventana (OSC 0/2) como nombre de la pestaña y el BEL o las
notificaciones (OSC 9 y similares) como aviso. Es la base genérica sobre
la que [[Plugin de CLIs de agentes de IA]] añade lo específico de cada CLI.

## Análisis

- `TerminalEmulator` ya lee los OSC, pero solo atiende OSC 7
  (directorio); el resto y el BEL se descartan.
- A decidir: si el título sustituye al nombre de la sesión o se muestra
  junto a él, qué hace un nombre puesto por el usuario, y qué tipo de
  aviso da el BEL (marca en la pestaña, notificación del sistema) y
  cuándo (solo si la pestaña no está a la vista).

## Criterios de finalización

- La pestaña muestra el título que pone el programa, según lo decidido.
- BEL y OSC 9 producen un aviso, en escritorio y en Android.
- Tests del emulador para los OSC nuevos, también troceados.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
