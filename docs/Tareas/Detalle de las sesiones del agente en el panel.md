---
Nombre: 'Detalle de las sesiones del agente en el panel'
Estado: 'Planificando'
Resumen: 'Petición del usuario (2026-09-30): que el panel del agente deje claro qué sesiones siguen vivas y se pueden recuperar, con datos que ayuden a reconocerlas. Por ejemplo, desde cuándo están activas y la fecha y hora en que empezaron. Hoy el panel solo da el estado, el último uso y la memoria. Ideas en dos grupos: las que salen de datos que el agente ya envía (inicio, tiempo activa, desde cuándo sin cliente, tamaño del historial, número de clientes) y las que piden cambiar el agente (shell, proceso en primer plano, directorio actual, tamaño del PTY, última salida, vista previa de la pantalla). Falta que el usuario elija cuáles.'
Decisiones: 'Amplía el panel de [[Estado y control del agente en la interfaz]], que se hizo en [[Transparencia y control del agente en el destino]]. Si cambia lo que se ve en el panel, se decide con el usuario y se recoge en esa decisión visual o en una nueva que la reemplace.'
Bloqueada: []
Fecha de creación: 2026-09-30T21:00:00+02:00
Última modificación: 2026-09-30T21:00:00+02:00
---

# Detalle de las sesiones del agente en el panel

## Objetivo

Que el usuario vea en el panel del agente qué sesiones siguen vivas en el
destino (recuperables al abrirlas), cuáles son antiguas y qué hay dentro de
cada una, sin tener que abrirlas. Surgió al ver en una pestaña restos del
centinela que parecían venir de una sesión antigua (ver
[[Sin rastro de la automatización en destinos Windows]]).

## Situación de partida

- `titan-agent --status --json` ya envía por sesión `createdMs`,
  `lastUsedMs`, `detachedMs`, `clients`, `closed`, `bufferBytes` y
  `memoryBytes` (`AgentSessionReport`, `control.go`).
- El panel (`AgentPanel.kt`) muestra por sesión un solo estado
  (`conectada ahora`, `en segundo plano · último uso hace X`,
  `sin conectar desde hace X`, `huérfana`) y la memoria. No usa `createdMs`,
  `detachedMs`, `bufferBytes` ni el número de clientes.

## Ideas

### Con los datos que ya envía el agente (solo app)

- **Inicio de la sesión**: fecha y hora (`creada el 27/09 16:10`) y tiempo
  activa (`activa desde hace 3 días`).
- **Desde cuándo está sin cliente**, con fecha y hora, además del "hace X".
- **Clientes conectados**: `en 2 pestañas o dispositivos` cuando hay más de uno.
- **Historial guardado** (`bufferBytes`): cuánto se reproducirá al reengancharse.
- **Etiqueta "recuperable"** clara frente a una sesión cuya shell ya terminó
  (`closed`), que hoy se oculta.
- Una fila desplegada con el detalle completo, para que la fila plegada siga
  corta.

### Con cambios en el agente

- **Shell** de la sesión (`cmd.exe`, `pwsh`, `bash`…) y su PID.
- **Proceso en primer plano**: qué está corriendo ahora (`claude`,
  `npm run dev`, `vim`…). Es lo que más ayuda a reconocer una sesión. En
  POSIX sale del grupo de primer plano del PTY; en Windows, del árbol de
  procesos bajo ConPTY.
- **Directorio actual** de la shell: fácil en Linux (`/proc/<pid>/cwd`) y
  difícil en Windows.
- **Última salida**: cuándo escribió algo por última vez, para saber si hay
  algo en marcha aunque nadie la mire.
- **Vista previa**: las últimas líneas de la pantalla o el título de la
  ventana (OSC 0/2).
- **Tamaño del PTY** (columnas × filas).
- **Versión del agente que creó la sesión**, útil tras actualizar.
- **CPU** de la sesión, junto a la memoria.

## Criterios de finalización

- El usuario elige qué datos se muestran y dónde (fila plegada o desplegada).
- El agente y `AgentSessionReport` reportan los datos nuevos, si los hay, con
  el esquema actualizado en los dos lados.
- Probado en el emulador contra el contenedor de pruebas y contra el sshd de
  Windows, con sesiones creadas en momentos distintos.

## Verificación

## Resultado
