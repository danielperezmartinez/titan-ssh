---
Nombre: 'Endurecer la entrada del mouse pad y del teclado del móvil'
Estado: 'Pendiente'
Resumen: 'Dos mejoras de endurecimiento de la entrada que salieron de la Auditoría 2026-10-01 Estándar. Una: si SendInput solo inserta parte de una combinación, una tecla modificadora (Ctrl, Alt, Win) puede quedar pulsada en el PC y convertir las teclas siguientes del usuario en atajos. Otra: el campo oculto que levanta el teclado del móvil no pide al IME que deje de aprender, y con el mouse pad es fácil teclear contraseñas en apps del escritorio.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-01T21:43:04+02:00
Última modificación: 2026-10-01T21:43:04+02:00
---

# Endurecer la entrada del mouse pad y del teclado del móvil

## Objetivo

Que la entrada que manda el móvil no deje el PC en un estado inesperado y que
lo tecleado no se quede en el teclado del móvil. Sale de la
[[Auditoría 2026-10-01 Estándar]] como endurecimiento: no son fallos que un
tercero pueda aprovechar. Es parte de lo que hay que tener antes de publicar
el mouse pad ([[Mouse pad en destinos Windows]]).

## Criterios de finalización

- [ ] **Modificadores atascados.** En `agent/internal/inject/` (`inject.go` y
  `inject_windows.go`), las teclas pulsadas con `ActionPress` se registran
  igual que las demás. Si `SendInput` inserta menos eventos de los pedidos,
  se sueltan todos los modificadores. `releaseAll` los suelta también al
  desconectarse. Hay un test con un `SendInput` simulado que inserta solo una
  parte.
- [ ] **Aprendizaje del IME.** El campo de captura del teclado del móvil
  (`TerminalView.kt`, que reutiliza el mouse pad) pide
  `IME_FLAG_NO_PERSONALIZED_LEARNING`. Si Compose no lo expone, se hace con
  un `InputConnection` propio o una vista de plataforma. Si no es posible,
  hay un interruptor de "modo contraseña" que cambia el tipo del campo.
  Afecta también al terminal, así que se prueba en los dos.

## Verificación

<Se rellena al completar: test del inyector, y comprobación en el emulador con
Gboard de que no aparecen sugerencias aprendidas de lo tecleado.>

## Resultado

<Se rellena al completar.>
