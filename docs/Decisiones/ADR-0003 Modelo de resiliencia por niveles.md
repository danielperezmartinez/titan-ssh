---
Nombre: Modelo de resiliencia de sesión por niveles
Número: 3
Estado: Aceptada
Resumen: 'La resiliencia ante microcortes se aborda en niveles: reconexión de cliente (base, sin requisitos en el servidor), envoltura automática en tmux/screen cuando existan, y un agente propio de titan-ssh instalable en el destino para persistencia total. Los detalles de implementación quedan pendientes.'
Decisión: Adoptar un modelo por niveles de resiliencia (reconexión de cliente + auto-tmux/screen + agente propio en el destino), todos dentro del alcance del proyecto.
Consecuencias: La reconexión de cliente no puede mantener vivo un proceso en primer plano ante una caída total; para persistencia real hace falta algo en el servidor (tmux/screen o el agente propio), con la fricción y el diseño de seguridad que ello implica.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T15:40:00+02:00
Última modificación: 2026-09-17T15:40:00+02:00
---

# ADR-0003 · Modelo de resiliencia de sesión por niveles

## Contexto

Uno de los diferenciadores clave frente a Termius es que los microcortes de red
no cierren la terminal ni pierdan el trabajo del usuario. Con SSH puro, al caer
la conexión TCP el shell remoto y su proceso en primer plano reciben SIGHUP y
mueren; una reconexión abre un shell nuevo. Mantener vivo el proceso exige
desacoplar la sesión de la conexión, lo que requiere algo en el servidor.

## Decisión

Abordar la resiliencia en **niveles**, todos dentro del alcance del proyecto:

1. **Reconexión de cliente (base).** La app detecta el corte, mantiene la pestaña
   en espera guardando su estado, reconecta automáticamente y restaura la
   vista/scrollback. Como mucho muestra un loader de "reconectando". Sin
   requisitos en el servidor. Es el nivel mínimo garantizado.
2. **Auto-tmux/screen cuando existan.** Si el destino tiene tmux/screen, envolver
   la sesión para que el proceso sobreviva a la caída y reengancharse al
   reconectar. Sin instalación adicional si ya están presentes.
3. **Agente propio en el destino.** Componente ligero de titan-ssh instalable en
   la máquina remota que mantiene PTYs persistentes y hace buffer/replay al
   reconectar, para persistencia total ("app en ambos extremos").

## Alternativas consideradas

- **Solo reconexión de cliente** — insuficiente para "no perder nunca el
  trabajo" ante una caída total.
- **Depender de mosh** — sobrevive a cortes y cambios de IP, pero exige mosh en
  ambos extremos y puertos UDP; se prefiere que el nivel 3 sea un agente propio
  con control total sobre buffering/replay y estado.

## Consecuencias

- Positivas: garantía mínima universal (nivel 1) y persistencia total opcional
  (niveles 2-3) según lo que el usuario pueda preparar en el destino.
- Negativas / compromisos: los niveles 2-3 implican preparar algo en el servidor;
  el agente propio añade fricción de instalación y una superficie de seguridad
  que hay que diseñar con cuidado. La librería SSH y el detalle de cada nivel
  quedan pendientes de definir.

Ver la tarea [[Resiliencia de sesión ante microcortes de red]].
