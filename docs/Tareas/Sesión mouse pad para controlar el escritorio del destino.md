---
Nombre: 'Sesión mouse pad para controlar el escritorio del destino'
Estado: 'Planificando'
Resumen: 'Tarea paraguas de un tipo de sesión nuevo, "mouse pad": el móvil hace de touchpad y de teclado del PC al que se conecta, reutilizando la conexión SSH y el agente titan-agent (transporte exec, instalación por SO y arquitectura, encuentro loopback con token). Lo nuevo es inyectar ratón y teclado en el escritorio del destino, que depende del sistema; por eso se divide en tres subtareas: Windows primero (la base común y el caso sencillo), después Linux X11 y Linux Wayland, que se abordan solo si hace falta. Antes de implementar hace falta una ADR que fije el tipo de sesión, el protocolo de entrada y el ayudante de escritorio de Windows.'
Decisiones: 'Decisiones del usuario del 2026-10-01: destinos sobre todo Windows, con soporte deseado para Linux X11 y Wayland; ratón y teclado; en Windows se acepta una tarea programada visible y gestionable desde el panel del agente para lanzar el ayudante en el escritorio del usuario; se empieza por Windows. Reutiliza [[ADR-0008 Diseño del agente de resiliencia nivel 3]] y [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. Los cierres explícitos siguen [[ADR-0014 Sesiones del agente sin caducidad]].'
Bloqueada: []
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-01T18:25:00+02:00
---

# Sesión mouse pad para controlar el escritorio del destino

## Objetivo

Añadir un tipo de sesión **mouse pad** con el que el móvil controla el ratón
y el teclado del PC al que se conecta. La sesión usa la misma conexión SSH y
el mismo agente que el nivel 3, así que no abre puertos ni añade una
autenticación propia.

## Subtareas y orden

| # | Subtarea | Depende de |
|---|---|---|
| 1 | [[Mouse pad en destinos Windows]] | — (incluye la ADR y la base común) |
| 2 | [[Mouse pad en destinos Linux X11]] | 1 |
| 3 | [[Mouse pad en destinos Linux Wayland]] | 1 |

2 y 3 son independientes entre sí y solo se abordan si el usuario lo pide.

## Lo que se reutiliza

- **Transporte**: `AgentTransport` ya lanza `titan-agent` con `exec` sobre el
  canal SSH autenticado ([[ADR-0008 Diseño del agente de resiliencia nivel 3]]).
- **Instalación**: subida por SFTP con detección de SO y arquitectura y
  comprobación de SHA-256 ([[Instalación del agente en destinos Windows y multi-SO]]).
  El inyector va dentro del mismo binario.
- **Encuentro local**: el patrón de TCP en `127.0.0.1` con token de
  [[titan-agent punto de encuentro TCP loopback con token]] sirve para hablar
  con un proceso ayudante.
- **Panel del agente**: lo que deje el mouse pad en el destino se ve y se quita
  desde allí ([[Transparencia y control del agente en el destino]]).

## Base común (propuesta, a cerrar en la ADR)

Se construye en la subtarea de Windows y la reutilizan las otras dos.

- **Modelo**: `Session` gana un tipo (`TERMINAL` o `MOUSEPAD`), con migración
  de la configuración. Ruta inicial, scripts, túneles y nivel de resiliencia
  no aplican a un mouse pad.
- **Protocolo**: un modo nuevo del agente (por ejemplo `--input`) con tramas
  de entrada: movimiento relativo, botón (pulsar y soltar), rueda vertical y
  horizontal, texto Unicode y tecla con modificadores. Al ser eventos sueltos,
  no hay ring buffer ni replay: al reconectar se sigue enviando. Los
  movimientos se agrupan por fotograma para no saturar el canal. Si el agente
  no puede inyectar, responde con un código del contrato `TITAN_AGENT_ERROR`
  (por ejemplo, sin escritorio iniciado).
- **Interfaz**: una pestaña propia con una superficie de gestos (arrastrar un
  dedo mueve, tocar es clic, tocar con dos dedos es clic derecho, arrastrar
  con dos dedos es scroll, pulsación larga y arrastre para arrastrar), botones
  visibles de clic, y teclado con el mismo campo de captura del IME que usa el
  terminal, más una fila de teclas especiales (Esc, Tab, flechas, Ctrl, Alt,
  Win/Super). Pensado para Android; la ADR decide si el tipo se ofrece en
  escritorio.
- **Seguridad**: no da más privilegios que una shell del mismo usuario, pero
  sí control del escritorio. La ADR lo deja explícito.
- **Límites comunes**: hace falta un usuario con la sesión gráfica iniciada,
  y no se controla la pantalla de bloqueo.

## Criterios de finalización

- Las subtareas que el usuario decida abordar están en `Hecha`; las que no, en
  `Archivada` con el motivo.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
