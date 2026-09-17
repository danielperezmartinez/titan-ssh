---
Nombre: "Autenticación SSH: signer en hardware y verificación de host"
Estado: En curso
Resumen: Autenticación SSH sobre el motor: verificación de host TOFU (known_hosts) verificada headless y contra host real (hecho), y signer delegado en clave no exportable (Android Keystore EC P-256 vía DelegatedKeyProvider; fallback software por PEM) implementado y compile-verified. Falta SOLO validar la firma real en hardware en un dispositivo/emulador Android para cerrar la tarea.
Decisiones: Sigue [[ADR-0005 Autenticación SSH y verificación de host]] y [[ADR-0004 Librería SSH]].
Bloqueada: []
Fecha de creación: 2026-09-17T19:20:00+02:00
Última modificación: 2026-09-17T21:30:00+02:00
---

# Autenticación SSH: signer en hardware y verificación de host

## Objetivo

Cerrar los criterios de [[ADR-0005 Autenticación SSH y verificación de host]]
que se acoplan a la librería SSH y no podían implementarse en la fundación de
[[Almacenamiento seguro de credenciales]]: la firma delegada en una clave no
exportable respaldada por hardware y la verificación del servidor por
`known_hosts`. **Bloqueada por [[Motor de conexión SSH]]**, que cablea sshj y
aporta la sesión y el verificador de host inyectable sobre los que enchufar esto.

## Contexto de arranque

La fundación ya entregada aporta lo que esta tarea consume:

- El modelo de dominio `AuthMethod` / `SshKeyType` / `KeyStorage` (con la
  preferencia ed25519 > ECDSA P-256 > RSA y hardware > software) en
  `shared/commonMain` (`im.gar.titanssh.secret`).
- El `SecretStore` nativo para custodiar contraseñas, passphrases y la clave
  **software de fallback**. La clave **hardware no exportable** no pasa por el
  `SecretStore`: vive en el almacén de claves del SO y se referencia por alias.

## Criterios de finalización

> El cableado de sshj y el source set `jvmShared` los aporta
> [[Motor de conexión SSH]]; esta tarea construye encima.

- ✅ **Signer delegado en hardware** (compile-verified; firma real pendiente de
  dispositivo Android). `SshCredentials.HardwareKey(alias)` → el motor lo resuelve
  vía `HardwareKeyRegistry` a un `DelegatedKeyProvider` (sshj `KeyProvider`) que
  firma con la `PrivateKey` no exportable del Keystore por JCE. Android:
  `AndroidHardwareKeys` genera/usa una clave EC P-256 no exportable en Android
  Keystore/StrongBox (ed25519 en Keystore aún no es portable entre dispositivos;
  P-256 es la alternativa aceptada por ADR-0005) y expone su línea `authorized_keys`.
- ✅ **Fallback** por software: `SshCredentials.PrivateKey` (clave PEM custodiada
  en `SecretStore`), ya verificado contra host real. En escritorio no hay clave no
  exportable portable en v1, así que usa este fallback.
- ✅ **Verificación de host key** (TOFU con confirmación en el primer contacto).
  Implementada en `commonMain` como `KnownHostsVerifier` sobre un
  `KnownHostsStore`, enchufada en el verificador inyectable del motor. En vez de
  atarse a `OpenSSHKnownHosts` de sshj (que fijaría la lógica dentro de sshj), se
  reusa el seam del motor y se persiste en **formato `known_hosts` de OpenSSH**
  (`FileKnownHostsStore`), con las mismas garantías: acepta en primer contacto
  tras confirmación, casa en reconexión y **rechaza** una clave cambiada como
  posible MITM. Misma semántica que la ADR.
- ✅ Sin ninguna ruta que exponga la clave privada hardware fuera del almacén: la
  `PrivateKey` del Keystore es un handle no exportable; solo firma vía JCE, nunca
  se serializa ni sale del hardware.

## Verificación

**Verificación de host / known_hosts (hecho):**

- Headless: `KnownHostsVerifierTest` (`tests=4`) — primer contacto acepta y
  persiste, decline rechaza sin guardar, clave cambiada se rechaza sin preguntar,
  y round-trip del `FileKnownHostsStore` en formato OpenSSH (incluido
  `[host]:port`).
- Real (host `<host-de-pruebas>` por Tailscale): `SshjIntegrationTest`
  `known_hosts_tofu_persists...` — primer contacto persiste el host key ed25519,
  reconexión con prompt que rechaza-nuevos **sigue conectando** (match desde
  disco), y un store envenenado con clave distinta produce `SshHostKeyRejected`.

**Signer delegado (hecho, con matiz de verificación):**

- Headless: `DelegatedKeyProviderTest` (`tests=3`) — el `DelegatedKeyProvider`
  expone material y tipo SSH (`ecdsa-sha2-nistp256`), la línea OpenSSH tiene forma
  correcta, y el `HardwareKeyRegistry` resuelve null sin resolver y material con
  él. Build de ambos targets OK: el backend Android Keystore (`AndroidHardwareKeys`)
  **compila** contra el SDK.
- El path `authPublickey(KeyProvider)` que usa la delegación es el **mismo** que
  ya autentica de forma verificada por PEM contra el host real; `DelegatedKeyProvider`
  es otra implementación de ese `KeyProvider`.
- **Pendiente (dispositivo Android) — único punto abierto de la tarea:** la firma
  real en hardware (Android Keystore EC P-256 ↔ sshj) solo puede validarse en un
  dispositivo/emulador con un servidor que confíe en la clave. Medio previsto: el
  emulador Android disponible en esta máquina, o el móvil del usuario. Flujo:
  `AndroidHardwareKeys.ensureKey(alias)` → añadir su línea al `authorized_keys` de
  un host de pruebas → conectar con `SshCredentials.HardwareKey(alias)` y
  comprobar auth. Al validarlo, marcar la tarea `Hecha`.

## Resultado

<Se cierra al validar el signer en dispositivo.> Implementado hasta ahora, sobre
el motor:

- **Verificación de host TOFU** (`KnownHostsVerifier`, `KnownHostsStore` con
  `InMemoryKnownHostsStore` y `FileKnownHostsStore` en formato OpenSSH),
  verificada headless y contra host real. `HostKeyInfo` ampliado con la clave
  pública base64.
- **Signer delegado**: `SshCredentials.HardwareKey` + `HardwareKeyRegistry` +
  `DelegatedKeyProvider` (jvmShared) + `AndroidHardwareKeys` (Android Keystore EC
  P-256 no exportable, instalado desde `MainActivity`). Fallback software por
  `SshCredentials.PrivateKey`. La clave hardware nunca sale del almacén.
  Compile-verified; firma real pendiente de dispositivo Android.
