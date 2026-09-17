---
Nombre: "Motor de conexión SSH"
Estado: Pendiente
Resumen: Primitiva de conexión SSH sobre sshj: cablear sshj y el source set jvmShared, establecer una sesión autenticada con un AuthMethod inyectado, abrir un canal shell (PTY) con flujos de E/S, cierre limpio y heartbeat expuesto. Es la base sobre la que se apoyan la autenticación (signer/known_hosts), la resiliencia y el terminal multipestaña.
Decisiones: Sigue [[ADR-0004 Librería SSH]] y [[ADR-0002 Stack KMP y alcance multiplataforma]]; consume [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]].
Bloqueada: []
Fecha de creación: 2026-09-17T19:40:00+02:00
Última modificación: 2026-09-17T19:40:00+02:00
---

# Motor de conexión SSH

## Objetivo

Dotar al proyecto de la pieza que hoy falta: **establecer y sostener una conexión
SSH**. Es la primitiva de más bajo nivel del cliente, sobre la que se construyen
la autenticación fuerte, la resiliencia ante microcortes y el terminal
multipestaña. Sin ella esas tareas no pueden implementarse ni verificarse contra
un servidor real.

Usa **sshj** en el cliente ([[ADR-0004 Librería SSH]]) y vive en un source set
JVM compartido por Android y escritorio, según la arquitectura del `README`.

## Criterios de finalización

- **Cablear sshj**: añadirlo al catálogo de versiones y crear el source set
  intermedio **`jvmShared`** (del que dependen `androidMain` y `desktopMain`),
  donde reside sshj (pure-Java) y la lógica de conexión. `commonMain` solo ve la
  interfaz de conexión, no sshj.
- **Sesión autenticada**: abrir una sesión SSH a un host (hostname, puerto,
  usuario) autenticando con un `AuthMethod` inyectado (contraseña o clave
  software de la fundación [[Almacenamiento seguro de credenciales]]). El *signer*
  hardware lo añade [[Autenticación SSH signer en hardware y verificación de host]].
- **Canal shell (PTY)**: abrir un shell interactivo con flujos de entrada/salida
  y tamaño de terminal; `exec` de un comando puntual como caso secundario.
- **Verificador de host inyectable**: el motor acepta un verificador de host key;
  la política TOFU con `known_hosts` la implementa la tarea de autenticación.
- **Cierre y detección de caída**: cierre limpio de canal/sesión y keepalive /
  heartbeat de sshj expuesto para que el nivel 1 de
  [[Resiliencia de sesión ante microcortes de red]] detecte cortes.
- **Sin texto plano**: el material de credenciales lo aporta el `SecretStore`; el
  motor nunca lo persiste.

## Tareas que se apoyan en esta

- [[Autenticación SSH signer en hardware y verificación de host]] (bloqueada por
  esta).
- [[Resiliencia de sesión ante microcortes de red]] (nivel 1: reconexión sobre
  esta sesión).
- [[Terminal multipestaña con sesiones simultáneas]] (cada pestaña gobierna una
  conexión de este motor).

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
