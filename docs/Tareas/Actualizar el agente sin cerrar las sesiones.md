---
Nombre: 'Actualizar el agente sin cerrar las sesiones'
Estado: 'En curso'
Resumen: 'Hoy, al actualizar un agente de una versión anterior desde la app, se para el daemon y se cierran todas sus sesiones con lo que tuvieran corriendo. Se quiere poder actualizar sin perder nada: cada sesión pasa a vivir en su propio proceso guardián, que tiene el PTY y el búfer, y el daemon queda como enrutador que se puede sustituir por otra versión sin tocar las sesiones. Windows, Linux y macOS.'
Decisiones: 'El usuario eligió el 2026-10-14 separar las sesiones del daemon en procesos guardianes, frente a heredar los PTY con execve (no sirve en Windows) o dejar convivir dos daemons; recogido en [[ADR-0022 Procesos de sesión independientes del daemon del agente]], aceptada el mismo día.'
Bloqueada: []
Fecha de creación: 2026-10-14T16:35:00+02:00
Última modificación: 2026-10-14T17:00:00+02:00
---

# Actualizar el agente sin cerrar las sesiones

## Objetivo

Que actualizar el agente de un destino no cierre ninguna sesión ni los
procesos que corren dentro. Hoy el aviso de versión anterior
([[Transparencia y control del agente en el destino]]) ofrece actualizar
avisando de que se cierran las sesiones, porque el daemon es el padre de
cada shell y guarda su PTY y su búfer. Diseño en
[[ADR-0022 Procesos de sesión independientes del daemon del agente]].

## Alcance

- Modo guardián de sesión en el binario del agente: crea el PTY o ConPTY y
  el shell, guarda el búfer, offsets, acks, títulos, tamaño y primer plano
  (lo que hoy hace `internal/session`).
- El daemon crea guardianes, enruta hacia ellos las conexiones de sesión y
  reúne su información para `status`, `close` y `preview`; ya no tiene PTY.
- Registro de cada guardián en el directorio de estado, con permisos y
  comprobaciones como `agent.json`; un daemon que arranca recoge los
  guardianes vivos.
- Protocolo daemon ↔ guardián mínimo y versionado; un daemon nuevo maneja
  guardianes de versiones anteriores.
- En la app, actualizar el agente deja de avisar de que se cierran las
  sesiones; cerrar todas las sesiones pasa a ser una acción aparte.
- `TITAN_AGENT` y la limpieza de versiones instaladas: no se borra una
  versión con guardianes vivos y los shells antiguos siguen encontrando el
  agente.
- Transición desde los daemons actuales, que tienen las sesiones dentro.

## Preguntas abiertas

- Cómo pasa la conexión del daemon al guardián: reenviar bytes por el daemon
  (simple, un salto más) o entregar el socket (`SCM_RIGHTS` en Unix,
  `WSADuplicateSocket` en Windows).
- Cómo se contacta un guardián: puerto loopback con token propio, como el
  daemon, o un canal heredado del daemon que lo lanzó.
- Qué hacer con un guardián cuyo registro existe pero que no responde.

## Criterios de finalización

- Con sesiones abiertas (incluido un proceso de larga duración dentro),
  actualizar el agente a otra versión no cierra ninguna: la app reconecta y
  recibe la salida producida mientras tanto. Comprobado en Linux y en
  Windows.
- Las sesiones nuevas tras actualizar corren con la versión nueva.
- Matar el daemon a mano tampoco cierra las sesiones; el siguiente daemon las
  recupera.
- `status`, `close` y `preview` siguen funcionando con guardianes de la
  versión actual y de la anterior.
- Ningún guardián termina por tiempo ni por inactividad.
- Tests del agente (`go test -race`) en verde en Windows y Linux.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
