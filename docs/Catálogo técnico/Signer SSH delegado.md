---
Nombre: "Signer SSH delegado"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/jvmSharedMain/kotlin/im/gar/titanssh/ssh/HardwareSigner.kt"
Entrada pública: "im.gar.titanssh.ssh"
Resumen: "Firma SSH delegada en clave no exportable (ADR-0005). SshCredentials.HardwareKey(alias) → el motor resuelve vía HardwareKeyRegistry a un DelegatedKeyProvider (KeyProvider de sshj) que firma con la PrivateKey no exportable por JCE. Android: AndroidHardwareKeys genera/usa una clave EC P-256 no exportable en Android Keystore/StrongBox y expone su línea authorized_keys. Sin resolver (escritorio v1) el motor lanza SshHardwareKeyUnavailable → fallback a SshCredentials.PrivateKey. Delegación compile-verified; firma real en hardware pendiente de dispositivo Android."
Última modificación: 2026-09-17T21:15:00+02:00
---

# Signer SSH delegado

Fuente de verdad: la delegación en
[HardwareSigner.kt](../../shared/src/jvmSharedMain/kotlin/im/gar/titanssh/ssh/HardwareSigner.kt)
(`HardwareKeyRegistry`, `DelegatedKeyProvider`, `DelegatedKeyMaterial`) y el
backend Android en
[AndroidHardwareKeys.kt](../../shared/src/androidMain/kotlin/im/gar/titanssh/ssh/AndroidHardwareKeys.kt).

Se enchufa al motor como una credencial de [[SshConnector]]. La clave privada
hardware nunca sale del almacén: `PrivateKey` es un handle no exportable que solo
firma vía JCE. Gobernado por [[ADR-0005 Autenticación SSH y verificación de host]]
y detallado en [[Autenticación SSH signer en hardware y verificación de host]].
