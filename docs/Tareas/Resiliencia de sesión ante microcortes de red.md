---
Nombre: Resiliencia de sesión ante microcortes de red
Estado: Planificando
Resumen: 'Los microcortes de red no deben cerrar la terminal ni perder el trabajo del usuario. La pestaña queda en espera guardando su estado y se reanuda al reconectar, mostrando como mucho un loader de "reconectando". Alcance: modelo por niveles (reconexión de cliente + auto-tmux/screen + agente propio en el destino).'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]] y [[ADR-0004 Librería SSH]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:34:18+02:00
---

# Resiliencia de sesión ante microcortes de red

## Objetivo

Que los microcortes de red no cierren la terminal. La pestaña afectada debe
quedar en espera, almacenar su estado y reanudarse al recuperar la conexión, de
forma que para el usuario sea como si no hubiera pasado nada. Como mucho se
muestra un loader de "reconectando", pero **nunca se pierde el trabajo en curso**.
Este es uno de los diferenciadores principales frente a Termius.

## Criterios de finalización

- Un microcorte de red no cierra la pestaña ni la sesión.
- Se conserva el estado de la sesión y se reanuda automáticamente al reconectar.
- La única señal visible aceptable durante la interrupción es un loader de
  "reconectando".

## Alcance (modelo por niveles)

Los tres niveles están dentro del alcance del proyecto, según
[[ADR-0003 Modelo de resiliencia por niveles]]:

1. **Reconexión de cliente (base):** reconexión SSH por el lado del cliente y
   restauración del estado/scrollback de la pestaña, sin requisitos en el
   servidor. Es el mínimo garantizado. Se apoya en el heartbeat de sshj para
   detectar caídas rápido; el scrollback se mantiene en el cliente. Limitación:
   si la conexión cae del todo, un proceso en primer plano del shell remoto
   muere; al reconectar se abre un shell nuevo (se puede re-ejecutar el script de
   inicio).
2. **Auto-tmux/screen cuando existan:** envolver la sesión (p. ej.
   `tmux new -A -s titan-<sesión>`) si el multiplexor está en el destino, para
   que el proceso sobreviva a la caída y reconectar re-enganche.
3. **Agente propio en el destino:** componente de titan-ssh instalable en la
   máquina remota con PTYs persistentes y buffer/replay ("app en ambos
   extremos") para persistencia total; construible con Apache MINA SSHD si habla
   SSH (ver [[ADR-0004 Librería SSH]]).

La librería SSH ya está decidida (sshj cliente / MINA agente, [[ADR-0004 Librería SSH]]);
el detalle de implementación de cada nivel se afinará al construirlos.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
