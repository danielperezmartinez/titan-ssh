---
Nombre: Almacenamiento seguro de credenciales
Estado: Hecha
Resumen: Fundación SecretStore entregada y verificada (interfaz expect/actual + backends nativos Android Keystore y escritorio java-keyring, fail-closed sin texto plano; modelo de dominio de auth con preferencia ed25519). El signer delegado en hardware y la verificación known_hosts se movieron a [[Autenticación SSH signer en hardware y verificación de host]] por depender del motor SSH.
Decisiones: 'Sigue [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]]. Librería de escritorio elegida: java-keyring (Windows Credential Store / Linux Secret Service), fail-closed sin texto plano.'
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T19:35:00+02:00
---

# Almacenamiento seguro de credenciales

## Objetivo

Custodiar de forma segura las credenciales SSH (claves privadas, contraseñas)
del usuario. Al tratarse de conexiones SSH la seguridad es prioridad alta: nunca
se almacenan en texto plano y se delega en los mecanismos nativos del SO, según
[[ADR-0001 Credenciales en almacén nativo del SO]]. La estrategia de
autenticación la fija [[ADR-0005 Autenticación SSH y verificación de host]].

## Criterios de finalización

- ✅ Interfaz `SecretStore` (`expect`/`actual`) en el código compartido, con
  implementaciones nativas: Android (Keystore AES-GCM; biometría opcional como
  hook posterior), Windows (Credential Store, respaldo DPAPI) y Linux (Secret
  Service). Los dos backends de escritorio se resuelven vía java-keyring.
- ✅ Autenticación por clave **ed25519** preferida sobre contraseña: modelado en
  el dominio (`SshKeyType`/`KeyStorage`/`AuthMethod` con `byPreference()`). La
  *aplicación* de la preferencia en el handshake vive en la tarea del signer.
- ➡️ **Clave privada no exportable en hardware desde la v1** + *signer* delegado:
  se acopla a sshj (aún no cableado) → movido a
  [[Autenticación SSH signer en hardware y verificación de host]].
- ➡️ Verificación de host key vía `known_hosts` (TOFU): se acopla a sshj →
  movido a la misma tarea.
- ✅ No existe ninguna ruta que persista credenciales en texto plano: los backends
  fallan cerrado (`SecretStoreUnavailable`) si no hay almacén nativo.
- ✅ Librería de escritorio elegida: **java-keyring** (`com.github.javakeyring`).

## Verificación

- Build de ambos targets OK (`GRADLE_EXIT=0`, `BUILD SUCCESSFUL`):
  `:shared:compileAndroidMain`, `:shared:compileKotlinDesktop`,
  `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin`.
- **Comprobación real** del backend de escritorio contra el almacén nativo de
  Windows: test `DesktopSecretStoreTest` (round-trip put/get/remove/contains con
  bytes idénticos), `tests=1 skipped=0 failures=0`, 0,451 s (llamadas JNA reales
  al Credential Store). Fuente:
  `shared/src/desktopTest/kotlin/im/gar/titanssh/secret/DesktopSecretStoreTest.kt`.
- Android: **compile-verified**; el round-trip contra Keystore requiere
  dispositivo/emulador y se validará al ejecutar la app.

## Resultado

Entregada la **fundación SecretStore** (paquete `im.gar.titanssh.secret` en
`shared`):

- `commonMain`: interfaz `SecretStore` + `SecretRef` + excepciones
  (`SecretStoreUnavailable`, `SecretStoreOperationFailed`) + `wipe()`; fábrica
  `expect fun createSecretStore()`; modelo de dominio de autenticación
  (`AuthMethod`, `SshKeyType`, `KeyStorage`, `byPreference()`).
- `androidMain`: `AndroidKeystoreSecretStore` (clave maestra AES-256-GCM no
  exportable en `AndroidKeyStore`, cifra cada secreto a fichero app-private,
  nunca texto plano) + `AndroidSecretStoreContext` (inyección del `Context`,
  inicializado en `MainActivity`).
- `desktopMain`: `DesktopSecretStore` sobre java-keyring, fail-closed.

El *signer* delegado en hardware y la verificación `known_hosts` quedan en
[[Autenticación SSH signer en hardware y verificación de host]] por depender del
motor SSH (sshj), aún no presente. Pieza registrada en el catálogo técnico
([[SecretStore]]).
