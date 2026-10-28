---
Nombre: "Signer SSH delegado"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareSigner.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: "Firma SSH delegada en clave no exportable (ADR-0005). SshCredentials.HardwareKey(alias) → el motor resuelve vía HardwareKeyRegistry a un DelegatedKeyProvider (KeyProvider de sshj). Android: AndroidHardwareKeys genera/usa una clave EC P-256 no exportable en Android Keystore/StrongBox y firma por JCE; SshAndroidCrypto deja BouncyCastle disponible pero NO forzado para enrutar la firma al proveedor AndroidKeyStore. Escritorio (ADR-0033): la clave del TPM es un PlatformSigningKey (sign(data) → DER) y sshjConfig envuelve los KeyAlgorithm con withPlatformSigning para que la firme la plataforma (CNG o TPM 2.0) sin tocar la JCE; ecdsaSignatureDer pasa r||s a DER. Sin clave para el alias el motor lanza SshHardwareKeyUnavailable. Verificado firmando en hardware en un Android real, en el TPM de una VM Windows y contra swtpm en Linux."
Última modificación: 2026-10-28T19:20:00+01:00
---

# Signer SSH delegado

Fuente de verdad: la delegación en
[HardwareSigner.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareSigner.kt)
(`HardwareKeyRegistry`, `DelegatedKeyProvider`, `DelegatedKeyMaterial`), el
backend Android en
[AndroidHardwareKeys.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/AndroidHardwareKeys.kt)
y el ajuste de proveedores cripto en
[SshAndroidCrypto.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshAndroidCrypto.kt).

Los backends de escritorio están en `shared/src/desktopMain/.../ssh/`:
`HardwareKeyProvisioning.desktop.kt` (`DesktopHardwareKeys`),
`WindowsTpmKeys.kt`, `LinuxTpmKeys.kt` y `Tpm2.kt` (el cliente TPM 2.0). La
firma de plataforma (`PlatformSigningKey`, `withPlatformSigning`) está en
`HardwareSigner.kt` y se engancha en `SshjConfig.kt`.

Se enchufa al motor como una credencial de [[SshConnector]]. La clave privada
hardware nunca sale del almacén: `PrivateKey` es un handle no exportable que
firma por JCE (Android) o por la plataforma (TPM del escritorio). Gobernado por
[[ADR-0005 Autenticación SSH y verificación de host]] y
[[ADR-0033 Clave no exportable en el TPM del escritorio]]; detallado en
[[Autenticación SSH signer en hardware y verificación de host]] y
[[Clave en hardware en la app de escritorio]].
