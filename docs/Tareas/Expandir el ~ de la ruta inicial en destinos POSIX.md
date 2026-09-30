---
Nombre: 'Expandir el ~ de la ruta inicial en destinos POSIX'
Estado: 'Pendiente'
Resumen: 'La ruta inicial de una sesión se teclea como cd -- con la ruta entre comillas simples (ShellSyntax.cd), así que un ~ al principio no se expande: ~/proyecto falla con "No such file or directory". En un destino POSIX lo natural es escribir ~, que es además la forma que da el propio contenedor de pruebas. Hay que expandir el ~ y el ~/ del principio al directorio de inicio del usuario, sin abrir la puerta a expandir nada más de la ruta (variables, comodines, órdenes).'
Decisiones: 'Pedida por el usuario el 2026-09-30, al verlo en las pruebas de [[Detalle de las sesiones del agente en el panel]]. Hoy es así a propósito (el comentario de ShellSyntax.cd dice "taken literally, no variable or ~ expansion on POSIX"); este cambio lo sustituye solo para el ~ del principio.'
Bloqueada: []
Fecha de creación: 2026-09-30T22:30:00+02:00
Última modificación: 2026-09-30T22:30:00+02:00
---

# Expandir el ~ de la ruta inicial en destinos POSIX

## Objetivo

Que una ruta inicial como `~/proyecto` o `~` funcione en un destino POSIX, como
en cualquier terminal.

## Situación de partida

- `ShellSyntax.cd(POSIX, dir)` genera `cd -- '<dir>'`. Las comillas simples
  impiden cualquier expansión, también la del `~`.
- En las pruebas del 2026-09-30, la sesión de pruebas con ruta `~/proyecto`
  daba `bash: cd: ~/proyecto: No such file or directory`.
- En Windows (`cmd.exe`, PowerShell) no aplica: se queda como está.

## Propuesta de partida

- Un `~` solo, o un `~/` al principio, se escribe fuera de las comillas: `cd --
  ~/'proyecto'` (o `"$HOME"/'proyecto'`). El resto de la ruta sigue entre
  comillas simples, así que nada más se expande.
- `~usuario/…` se queda literal, salvo que el usuario decida lo contrario.

## Criterios de finalización

- `~` y `~/algo` llevan al directorio de inicio y a su subcarpeta en un
  destino POSIX. Una ruta con espacios, comillas, `$` o `*` sigue tomándose
  literalmente.
- Tests de `ShellSyntax` y una prueba contra el contenedor de pruebas
  (`tools/test-sshd/`) en el emulador.

## Verificación

## Resultado
