---
Nombre: "Signer SSH delegado"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareSigner.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: "Firma SSH delegada en clave no exportable (ADR-0005). SshCredentials.HardwareKey(alias) → el motor resuelve vía HardwareKeyRegistry a un DelegatedKeyProvider (KeyProvider de sshj) que firma con la PrivateKey no exportable por JCE. Android: AndroidHardwareKeys genera/usa una clave EC P-256 no exportable en Android Keystore/StrongBox y expone su línea authorized_keys; SshAndroidCrypto deja BouncyCastle disponible pero NO forzado para enrutar la firma al proveedor AndroidKeyStore. Sin resolver (escritorio v1) el motor lanza SshHardwareKeyUnavailable → fallback a SshCredentials.PrivateKey. Verificado firmando en hardware en un dispositivo real."
Última modificación: 2026-09-24T12:00:00+02:00
---

# Signer SSH delegado

Fuente de verdad: la delegación en
[HardwareSigner.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareSigner.kt)
(`HardwareKeyRegistry`, `DelegatedKeyProvider`, `DelegatedKeyMaterial`), el
backend Android en
[AndroidHardwareKeys.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/AndroidHardwareKeys.kt)
y el ajuste de proveedores cripto en
[SshAndroidCrypto.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshAndroidCrypto.kt).

Se enchufa al motor como una credencial de [[SshConnector]]. La clave privada
hardware nunca sale del almacén: `PrivateKey` es un handle no exportable que solo
firma vía JCE. Gobernado por [[ADR-0005 Autenticación SSH y verificación de host]]
y detallado en [[Autenticación SSH signer en hardware y verificación de host]].
