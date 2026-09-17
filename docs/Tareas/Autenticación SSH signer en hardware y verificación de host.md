---
Nombre: "Autenticación SSH: signer en hardware y verificación de host"
Estado: Hecha
Resumen: Autenticación SSH sobre el motor, verificada de punta a punta. Verificación de host TOFU (known_hosts) y signer delegado en clave no exportable (Android Keystore EC P-256 vía DelegatedKeyProvider; fallback software por PEM). La firma real en hardware se validó en un dispositivo real (StrongBox) contra el host de pruebas. Clave del arreglo en Android: BouncyCastle disponible pero NO forzado, para que la firma se enrute al proveedor AndroidKeyStore.
Decisiones: Sigue [[ADR-0005 Autenticación SSH y verificación de host]] y [[ADR-0004 Librería SSH]].
Bloqueada: []
Fecha de creación: 2026-09-17T19:20:00+02:00
Última modificación: 2026-09-18T00:40:00+02:00
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

- ✅ **Signer delegado en hardware** (verificado en dispositivo real).
  `SshCredentials.HardwareKey(alias)` → el motor lo resuelve vía
  `HardwareKeyRegistry` a un `DelegatedKeyProvider` (sshj `KeyProvider`) que firma
  con la `PrivateKey` no exportable del Keystore por JCE. Android:
  `AndroidHardwareKeys` genera/usa una clave EC P-256 no exportable en Android
  Keystore/StrongBox (ed25519 en Keystore aún no es portable entre dispositivos;
  P-256 es la alternativa aceptada por ADR-0005) y expone su línea `authorized_keys`.
  Para que la firma funcione en Android, `SshAndroidCrypto` deja BouncyCastle
  disponible pero **no forzado** como proveedor de sshj: así la firma usa
  selección perezosa y se enruta al proveedor AndroidKeyStore (forzar BC falla
  con «no encoding for EC private key» porque BC necesita el material de la clave).
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
- **Real en hardware (hecho):** APK de prueba instalado en **un dispositivo Android**;
  la app generó la clave EC P-256 no exportable en el Keystore (StrongBox), se
  enroló su pública en el `authorized_keys` del host `<host-de-pruebas>`, y la app
  conectó por Tailscale **autenticando con la firma del Keystore** (log:
  «Autenticado con la clave del Keystore»), abrió shell PTY y ejecutó un comando
  contra el shell real (`user=<usuario>;hostname=<host-de-pruebas>`). La pantalla de
  prueba vive en `androidApp` (`HardwareSignerTestScreen`, debug).
  Diagnóstico intermedio: sin BC en Android fallaba el KEX (`SshConnectFailed`);
  con BC forzado, la firma fallaba (`no encoding for EC private key`); el arreglo
  fue `SshAndroidCrypto` (BC disponible, no forzado).

## Resultado

Autenticación SSH cerrada y verificada de punta a punta sobre el motor:

- **Verificación de host TOFU** (`KnownHostsVerifier`, `KnownHostsStore` con
  `InMemoryKnownHostsStore` y `FileKnownHostsStore` en formato OpenSSH),
  verificada headless y contra host real. `HostKeyInfo` ampliado con la clave
  pública base64.
- **Signer delegado**: `SshCredentials.HardwareKey` + `HardwareKeyRegistry` +
  `DelegatedKeyProvider` (jvmShared) + `AndroidHardwareKeys` (Android Keystore EC
  P-256 no exportable, instalado desde `MainActivity`) + `SshAndroidCrypto` (BC
  disponible pero no forzado, para enrutar la firma al AndroidKeyStore). Fallback
  software por `SshCredentials.PrivateKey`. La clave hardware nunca sale del
  almacén. **Verificado firmando en hardware en un dispositivo real** contra el host de
  pruebas.
- Nota: `androidApp` muestra ahora una pantalla de prueba del signer
  (`HardwareSignerTestScreen`, debug); se sustituirá por la UI real al construir
  [[Terminal multipestaña con sesiones simultáneas]] / [[Panel de gestión de hosts y sesiones]].
