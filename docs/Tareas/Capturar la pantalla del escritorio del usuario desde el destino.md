---
Nombre: 'Capturar la pantalla del escritorio del usuario desde el destino'
Estado: 'Planificando'
Resumen: 'En Windows, la shell de una sesión de titan-ssh corre en la sesión 0 y no puede capturar la pantalla del escritorio del usuario. Esto impide, por ejemplo, que un agente de IA que trabaja en el PC desde una sesión de titan-ssh compruebe cómo se ve la app de escritorio que acaba de abrir con --desktop-run (ADR-0019). Idea: una orden del agente, titan-agent --desktop-capture, que pide la captura al ayudante de escritorio y la guarda como PNG. Queda fuera de ADR-0019 porque tiene su propio análisis de privacidad. Pendiente de decidir alcance, destino del fichero y garantías.'
Decisiones: 'El usuario pide el 2026-10-07 dejarla en planificación aparte de [[ADR-0019 Abrir programas en el escritorio del usuario desde el destino]].'
Bloqueada: []
Fecha de creación: 2026-10-07T10:59:12+02:00
Última modificación: 2026-10-07T10:59:12+02:00
---

# Capturar la pantalla del escritorio del usuario desde el destino

## Objetivo

Que desde el terminal de una sesión de titan-ssh contra un destino Windows se
pueda obtener una captura de la pantalla del escritorio del usuario, como
fichero PNG, para comprobar lo que se ha abierto con
[[Abrir programas en el escritorio del usuario desde el destino]] sin que el
usuario tenga que mirar el PC.

## Contexto

- La shell corre en la sesión 0, sin escritorio: no puede leer la pantalla
  de la sesión interactiva.
- El ayudante de escritorio
  ([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]) sí corre
  en ella y ya es alcanzable desde la shell con el apretón de manos de
  [[ADR-0018 Autenticación mutua en el punto de encuentro del agente]].
- Con [[ADR-0019 Abrir programas en el escritorio del usuario desde el destino]]
  el usuario ya podría capturar la pantalla lanzando él mismo un programa que
  lo haga. La orden no añadiría privilegios, pero sí la vuelve cómoda e
  invisible, y una captura puede contener contraseñas, mensajes o datos de
  otras personas. Por eso necesita su propio análisis.

## Por decidir

- **Alcance**: todo el escritorio virtual, un monitor o una ventana concreta
  (por título o PID, por ejemplo la del programa abierto con
  `--desktop-run`).
- **Destino del fichero**: el directorio de estado privado, una ruta que
  elige quien llama o la salida estándar. Cuánto tiempo se conserva.
- **Pantalla de bloqueo, UAC y escritorio seguro**: el ayudante no puede
  leerlos; la orden debe fallar con un error claro y no devolver una imagen
  negra como si fuera buena.
- **Transparencia**: registrar cada captura como los lanzamientos, y si se
  avisa en el propio escritorio (por ejemplo, una notificación) cuando se
  hace una.
- **Escala y varios monitores**: DPI por monitor y coordenadas del
  escritorio virtual.
- **Linux X11 y Wayland**: si la orden se ofrece también allí, con los
  ayudantes de [[Mouse pad en destinos Linux X11]] y
  [[Mouse pad en destinos Linux Wayland]].
- Si hace falta una ADR propia o basta con ampliar ADR-0019.

## Criterios de finalización

- Decisiones anteriores cerradas con el usuario y recogidas en una ADR.
- Criterios de verificación concretos al pasar la tarea a `Pendiente`.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
