---
Nombre: "Autenticación SSH: signer en hardware y verificación de host"
Estado: En curso
Resumen: Integrar en el motor SSH la autenticación con clave no exportable respaldada por hardware (signer delegado que firma sin exponer la clave) y la verificación de host key vía known_hosts (TOFU con confirmación). Se separó de [[Almacenamiento seguro de credenciales]] por depender de sshj, aún no cableado.
Decisiones: Sigue [[ADR-0005 Autenticación SSH y verificación de host]] y [[ADR-0004 Librería SSH]].
Bloqueada: []
Fecha de creación: 2026-09-17T19:20:00+02:00
Última modificación: 2026-09-17T20:55:00+02:00
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

- ⏳ **Signer delegado en hardware**: generar/usar una clave ed25519 (o la
  soportada) no exportable en Android Keystore / StrongBox y, en escritorio, el
  equivalente disponible; integrar un `Signer`/`KeyProvider` propio que sshj
  delega para firmar el challenge sin que la clave salga del hardware.
- ⏳ **Fallback** por software (clave custodiada en `SecretStore`) donde no haya
  soporte de hardware o formato de clave.
- ✅ **Verificación de host key** (TOFU con confirmación en el primer contacto).
  Implementada en `commonMain` como `KnownHostsVerifier` sobre un
  `KnownHostsStore`, enchufada en el verificador inyectable del motor. En vez de
  atarse a `OpenSSHKnownHosts` de sshj (que fijaría la lógica dentro de sshj), se
  reusa el seam del motor y se persiste en **formato `known_hosts` de OpenSSH**
  (`FileKnownHostsStore`), con las mismas garantías: acepta en primer contacto
  tras confirmación, casa en reconexión y **rechaza** una clave cambiada como
  posible MITM. Misma semántica que la ADR.
- ⏳ Sin ninguna ruta que exponga la clave privada hardware fuera del almacén
  (parte del signer).

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

**Signer en hardware:** pendiente (siguiente incremento de esta tarea).

## Resultado

<Se completa al cerrar el signer. Hecho hasta ahora: verificación de host TOFU
(`KnownHostsVerifier`, `KnownHostsStore`/`InMemoryKnownHostsStore`,
`FileKnownHostsStore`) sobre el seam del motor; `HostKeyInfo` ampliado con la
clave pública base64 para persistir/comparar en formato known_hosts.>
