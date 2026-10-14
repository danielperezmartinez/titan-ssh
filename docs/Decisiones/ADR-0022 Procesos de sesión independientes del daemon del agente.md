---
Nombre: 'Procesos de sesión independientes del daemon del agente'
Número: 22
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-14, para poder actualizar el agente sin perder las sesiones. Hoy el daemon es el padre de cada shell y guarda su PTY y su búfer, así que al pararlo para actualizar se cierran todas. Se propone separar las dos funciones: cada sesión vive en su propio proceso pequeño (el guardián de sesión), que tiene el PTY y el búfer y habla un protocolo interno mínimo y estable; el daemon queda como enrutador sin estado que se puede parar y sustituir por otra versión sin que las sesiones se enteren. Igual en Windows, Linux y macOS.'
Decisión: 'Cada sesión del agente corre en un proceso guardián propio, que la conserva aunque el daemon se pare o se actualice; el daemon solo enruta las conexiones y el control hacia los guardianes.'
Consecuencias: 'Actualizar el agente deja de cerrar las sesiones: las abiertas siguen en su guardián de la versión con la que nacieron y las nuevas usan la nueva. Un fallo del daemon tampoco las tumba. A cambio hay un proceso más por sesión, el protocolo entre daemon y guardián tiene que versionarse y cambiar muy poco, y no se puede borrar el binario de una versión mientras alguno de sus guardianes siga vivo. Sustituye en parte el modelo de proceso de [[ADR-0008 Diseño del agente de resiliencia nivel 3]]. Se implementa en [[Actualizar el agente sin cerrar las sesiones]].'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-14T16:35:00+02:00
Última modificación: 2026-10-14T17:00:00+02:00
---

# ADR-0022 · Procesos de sesión independientes del daemon del agente

## Contexto

Cuando la app se actualiza, avisa en los agentes de versiones anteriores y
ofrece actualizarlos ([[Transparencia y control del agente en el destino]]).
Actualizar hoy es `--stop` y arrancar la versión nueva, y eso **cierra todas
las sesiones** del destino, con lo que estuviera corriendo dentro (un
`claude`, una compilación…). Va contra el primer motivo del proyecto:
sobrevivir sin perder el trabajo del usuario.

La causa está en el modelo de proceso de
[[ADR-0008 Diseño del agente de resiliencia nivel 3]] (y su versión portable,
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]]): un único daemon
por usuario es el padre de cada shell y guarda el maestro del PTY (Unix) o el
ConPTY (Windows), el búfer circular y el estado de cada sesión. Si el proceso
termina, se cierra su lado del PTY y los shells reciben SIGHUP o se terminan.

Guardar el estado en disco y reconstruirlo no lo resuelve: se podría
recuperar el scrollback y el directorio de trabajo, pero no los procesos en
marcha, que es lo que importa.

## Decisión

Aceptada por el usuario el 2026-10-14, elegida entre las alternativas de
abajo:

1. **Guardián de sesión.** Cada sesión vive en un proceso propio, lanzado por
   el daemon con el mismo binario del agente en un modo interno (p. ej.
   `--session-host`). El guardián crea el PTY o ConPTY y el shell, guarda el
   búfer circular, los offsets y los acks, y hace lo que hoy hace `Session`
   (replay, títulos, primer plano, tamaño). En Windows nace ya fuera del Job
   Object de la sesión SSH, igual que hoy los shells del daemon.
2. **Daemon sin estado de sesión.** El daemon mantiene el lock de instancia
   única, el punto de encuentro con token
   ([[ADR-0018 Autenticación mutua en el punto de encuentro del agente]]) y
   las operaciones de control (`status`, `close`, `preview`, `stop`), pero no
   tiene PTY ni búfer: crea guardianes y les pasa las conexiones (reenviando
   los bytes, o pasando el socket cuando la plataforma lo permita).
3. **Descubrimiento.** Cada guardián publica en el directorio de estado su
   registro (id de sesión, versión, PID, cómo contactarlo) con los mismos
   permisos y comprobaciones que `agent.json`. Un daemon que arranca recoge
   los guardianes vivos que encuentra y sigue enrutando hacia ellos.
4. **Protocolo interno estable.** Lo que el daemon habla con el guardián es
   mínimo, va versionado y casi no cambia, para que un daemon nuevo maneje
   guardianes de versiones anteriores. Si un guardián es demasiado antiguo
   para algo nuevo, esa sesión pierde solo esa función, no la sesión.
5. **Actualizar ≠ cerrar todo.** Actualizar el agente para solo el daemon y
   arranca el nuevo; las sesiones abiertas siguen en sus guardianes y las
   nuevas nacen con la versión nueva. Cerrar todas las sesiones pasa a ser
   una acción aparte y explícita. La app solo ve un microcorte: reconecta con
   su offset y recibe lo perdido.
6. **Sin cierre por tiempo.** Un guardián termina cuando su shell termina o
   cuando el usuario cierra la sesión, nunca por inactividad
   ([[ADR-0014 Sesiones del agente sin caducidad]]).
7. **Versiones instaladas.** No se borra el binario de una versión mientras
   quede algún guardián suyo vivo. Los shells reciben en `TITAN_AGENT` una
   ruta que siga funcionando tras actualizar (un nombre estable, o la de la
   versión mientras exista), para que `--desktop-run` y `--status` lanzados
   desde una sesión antigua no apunten a un fichero borrado.

Los detalles (formato del registro, cómo pasa la conexión, transición desde
los daemons actuales) se cierran en la tarea.

## Alternativas consideradas

- **Guardar el estado y reconstruir tras reiniciar.** Descartada como
  solución: no recupera los procesos en marcha. Como mucho, último recurso.
- **Heredar los PTY en el mismo proceso (`execve`).** El daemon viejo deja
  abiertos los PTY, el listener y el lock, escribe su estado en un pipe y se
  convierte en el binario nuevo con el mismo PID, así que los shells siguen
  siendo sus hijos. Es la técnica de nginx o HAProxy y funciona bien en
  Linux, macOS y FreeBSD. Descartada porque en Windows no hay `execve` y el
  handle del pseudoconsola (`HPCON`), necesario para redimensionar, no se
  puede pasar a otro proceso de forma soportada; Windows es un destino
  principal (mouse pad, escritorio).
- **Dejar convivir el daemon viejo y el nuevo.** El viejo sigue con sus
  sesiones y el nuevo atiende las nuevas. Descartada: deja dos daemons vivos
  sin forma de que el viejo desaparezca solo (el agente nunca se cierra por
  su cuenta), y complica el lock y el enrutado.
- **Mantener el aviso actual** (actualizar cierra las sesiones). Es lo que
  hay; el usuario quiere poder actualizar sin perder nada.

## Consecuencias

- Positivas: actualizar el agente no cierra sesiones en ningún sistema; un
  fallo o cierre inesperado del daemon tampoco; el daemon queda más simple y
  se puede reiniciar sin miedo.
- Negativas / compromisos: un proceso más por sesión (unos pocos MB cada
  uno); un protocolo interno que hay que versionar con cuidado; las sesiones
  antiguas no reciben las mejoras del guardián hasta que se cierran;
  `status` y `preview` tienen que reunir la información de varios procesos;
  hay que gestionar la transición desde los daemons actuales, que sí tienen
  las sesiones dentro (esos se actualizarán una última vez cerrándolas).
