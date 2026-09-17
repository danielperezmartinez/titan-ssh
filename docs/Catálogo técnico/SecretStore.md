---
Nombre: "SecretStore"
Tipo: "Contrato"
Área: "Seguridad"
Feature: "Custodia de credenciales"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/secret/SecretStore.kt"
Entrada pública: "im.gar.titanssh.secret"
Resumen: "Contrato expect/actual que custodia material secreto (contraseñas, passphrases, claves software de fallback) en el almacén nativo del SO sin texto plano. put/get/remove/contains sobre SecretRef; falla cerrado con SecretStoreUnavailable si no hay almacén. Se construye con createSecretStore(). Backends: Android Keystore (AES-GCM) y escritorio java-keyring (Windows Credential Store / Linux Secret Service)."
Última modificación: 2026-09-17T19:20:00+02:00
---

# SecretStore

Después de descubrir esta pieza en el catálogo, consulta
[su implementación](../../shared/src/commonMain/kotlin/im/gar/titanssh/secret/SecretStore.kt)
como fuente de verdad de su contrato detallado.

Piezas relacionadas del mismo paquete `im.gar.titanssh.secret`:

- `SecretRef` — handle opaco (alias) de un secreto; nunca lleva material.
- `AuthMethod` / `SshKeyType` / `KeyStorage` — modelo de dominio de autenticación
  SSH con la preferencia de [[ADR-0005 Autenticación SSH y verificación de host]]
  (ed25519 sobre password; hardware sobre software).
- `createSecretStore()` — fábrica `expect`/`actual` que devuelve el backend de la
  plataforma en curso.

Gobernada por [[ADR-0001 Credenciales en almacén nativo del SO]]. El *signer*
delegado en hardware y la verificación `known_hosts` se construyen en
[[Autenticación SSH signer en hardware y verificación de host]].
