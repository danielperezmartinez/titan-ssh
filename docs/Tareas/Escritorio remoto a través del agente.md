---
Nombre: 'Escritorio remoto a través del agente'
Estado: 'En curso'
Resumen: 'Tarea paraguas de un tipo de sesión nuevo, escritorio remoto (opción D): ver y controlar la pantalla del destino desde titan-ssh sin RDP ni VNC de terceros, sobre la conexión SSH y titan-agent. Añade la mitad que le falta al mouse pad: captura en el destino, codificación por baldosas, transporte por el canal exec con control de flujo por acuses, decodificación y pintado en Compose. Se divide por sistema: Linux X11 primero (se puede probar entero aquí con Xvfb en Docker), después Windows y Linux Wayland.'
Decisiones: 'Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]] (Propuesta, 2026-10-21): baldosas FILL/ZLIB/JPEG con la biblioteca estándar, sin vídeo ni dependencias nuevas; un solo canal --screen para imagen y entrada; como mucho dos fotogramas sin acuse. Reutiliza [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]] y [[ADR-0018 Autenticación mutua en el punto de encuentro del agente]]. Para Windows y cualquier Linux, [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]] (2026-10-21): sesión gráfica por logind, portal + PipeWire propio en KDE/GNOME, protocolos wlroots, GDI y Desktop Duplication en Windows, todos los monitores y un catálogo de errores. Independiente del cliente RDP embebido (opción C) que lleva otra línea de trabajo. Las dos opciones van a la vez a la canary para compararlas: DESKTOP es un tipo experimental que solo ofrece la canary (mecanismo común SessionType.experimental/offered, acordado con la opción C el 2026-10-21); la estable llevará solo la elegida y la otra se retira entera.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-21T19:00:00+02:00
---

# Escritorio remoto a través del agente

## Objetivo

Un tipo de sesión **escritorio remoto** que enseña la pantalla del destino y
la controla con el ratón, el teclado o el táctil, desde Android y desde el
escritorio, sin instalar ni abrir nada más en el destino. La sesión usa la
misma conexión SSH y el mismo agente que el mouse pad
([[Sesión mouse pad para controlar el escritorio del destino]]).

## Subtareas y orden

| # | Subtarea | Depende de |
|---|---|---|
| 1 | [[Escritorio remoto en destinos Linux X11]] | — (incluye la base común: tipo de sesión, protocolo, vista en la app) |
| 2 | [[Sesión gráfica del destino y errores del escritorio remoto]] | 1 |
| 3 | [[Escritorio remoto en destinos Linux Wayland]] (KDE, GNOME) | 2 |
| 4 | [[Escritorio remoto en destinos Linux wlroots]] | 2 |
| 5 | [[Escritorio remoto en destinos Windows]] | 2 |
| 6 | [[Documentación de usuario del escritorio remoto]] | 3, 4, 5 |

Las subtareas 3, 4 y 5 son independientes entre sí y se pueden hacer en
paralelo una vez hecha la 2 ([[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]).

## Relacionadas

- [[Capturar la pantalla del escritorio del usuario desde el destino]]: una
  captura suelta a PNG desde la shell; puede reutilizar los capturadores de
  esta tarea.
- [[Mouse pad en destinos Linux X11]]: el cliente X11 con XTEST que se
  escribe aquí es también su inyector.

## Criterios de finalización

- ADR-0029 aceptada por el usuario (o sustituida por la que acuerde).
- Las subtareas que el usuario decida abordar están en `Hecha`; las que no, en
  `Archivada` con el motivo.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
