---
Nombre: "Motor de conexión SSH"
Estado: Hecha
Resumen: Primitiva de conexión SSH sobre sshj entregada y verificada (headless + handshake real contra un host por Tailscale): sshj + source set jvmShared cableados, sesión autenticada por clave, canal shell (PTY) con flujos de E/S, cierre limpio y heartbeat expuesto. Base sobre la que se apoyan la autenticación (signer/known_hosts), la resiliencia y el terminal multipestaña.
Decisiones: Sigue [[ADR-0004 Librería SSH]] y [[ADR-0002 Stack KMP y alcance multiplataforma]]; consume [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]].
Bloqueada: []
Fecha de creación: 2026-09-17T19:40:00+02:00
Última modificación: 2026-09-17T20:30:00+02:00
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

**Headless (hecho):**

- Build de ambos targets OK (`GRADLE_EXIT=0`, `BUILD SUCCESSFUL`):
  `:shared:compileAndroidMain`, `:shared:compileKotlinDesktop` (incluye el nuevo
  source set `jvmSharedMain` con sshj), `:androidApp:compileDebugKotlin`,
  `:desktopApp:compileKotlin`.
- Tests `SshjConnectorTest` (`:shared:desktopTest`): `tests=3 skipped=0
  failures=0` — la fábrica devuelve conector, un intento a un puerto cerrado se
  mapea a `SshConnectFailed`, y el fingerprint tiene la forma `SHA256:` estándar
  (32 bytes, base64 sin padding). Fuente:
  `shared/src/desktopTest/kotlin/im/gar/titanssh/ssh/SshjConnectorTest.kt`.
- Warning conocido y benigno de Gradle: «Default Kotlin Hierarchy Template Not
  Applied Correctly», por insertar el source set intermedio `jvmShared`; no
  afecta a la compilación ni a los tests.

**Handshake real (hecho):** verificado contra un host real (`<host-de-pruebas>`,
usuario `<usuario>`) por Tailscale, con `SshjIntegrationTest` (opt-in, se salta sin
credenciales; la clave privada se lee de disco en tiempo de ejecución, nunca del
repo). Resultado: host key `ssh-ed25519
SHA256:<huella-del-host>` entregado y con fingerprint
calculado; autenticación por clave correcta (llega a `CONNECTED`); shell PTY con
E/S real (el shell remoto devolvió su secuencia de integración confirmando
`user=<usuario>;hostname=<host-de-pruebas>`). Fuente:
`shared/src/desktopTest/kotlin/im/gar/titanssh/ssh/SshjIntegrationTest.kt`
(el reenvío de datos de conexión vía `-P` está en `shared/build.gradle.kts`).

## Resultado

Primitiva de conexión SSH entregada (paquete `im.gar.titanssh.ssh`):

- **`commonMain` (contrato, sin sshj):** `SshEndpoint`, `SshCredentials`
  (`Password` / `PrivateKey`), `HostKeyVerifier` + `HostKeyInfo`, `SshSession`,
  `SshShell`, `SshConnectionState`, `SshConnector`, excepciones
  (`SshConnectFailed`/`SshHostKeyRejected`/`SshAuthFailed`) y la fábrica
  `expect fun createSshConnector()`.
- **`jvmSharedMain` (impl sshj, compartida Android+escritorio):** `SshjConnector`
  (conexión, keepalive/heartbeat, verificador de host que calcula el fingerprint
  SHA256 y delega la confianza, autenticación password/clave), `SshjSession`
  (estado observable + watcher de caída) y `SshjShell` (PTY, flujo de salida por
  `Channel`, envío de entrada, `resize` real vía `changeWindowDimensions`). Un
  único `actual` de la fábrica sirve a los dos targets.

Toolchain: sshj 0.39.0 (BouncyCastle opcional) + eddsa 0.3.0 para ed25519, en el
source set `jvmShared` del `README`. La preferencia de método/clave (ed25519) ya
vive en el modelo de [[Almacenamiento seguro de credenciales]]; el signer
hardware y `known_hosts` los añade
[[Autenticación SSH signer en hardware y verificación de host]] sobre esta base.
