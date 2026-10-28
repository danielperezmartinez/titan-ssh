---
Nombre: "Aprovisionamiento de clave hardware"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareKeyProvisioning.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: "Seam expect/actual para generar/reutilizar desde commonMain una clave no exportable en el almacén de claves del SO y obtener su línea authorized_keys (ADR-0005). hardwareKeyUnavailableReason() devuelve null si se puede o el motivo legible si no (sustituye a isHardwareKeyProvisioningSupported); ensureHardwareKey(alias) genera bajo el alias elegido y devuelve la línea OpenSSH (o lanza HardwareKeyUnsupported con el motivo). Android: AndroidHardwareKeys (EC P-256 en Android Keystore/StrongBox). Escritorio (ADR-0033): DesktopHardwareKeys con EC P-256 en el TPM 2.0, WindowsTpmKeys (CNG Platform Crypto Provider) o LinuxTpmKeys (/dev/tpmrm0, grupo tss); sin TPM usable el motivo dice por qué. La privada nunca sale del hardware; el editor de host lo usa para 'Generar / mostrar clave' y para instalarla en el host."
Última modificación: 2026-10-28T19:20:00+01:00
---

# Aprovisionamiento de clave hardware

Fuente de verdad: el seam en
[HardwareKeyProvisioning.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareKeyProvisioning.kt)
y su `actual` de Android en
[HardwareKeyProvisioning.android.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareKeyProvisioning.android.kt)
(sobre [[Signer SSH delegado]] / `AndroidHardwareKeys`) y el `actual` de escritorio
[HardwareKeyProvisioning.desktop.kt](../../shared/src/desktopMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/HardwareKeyProvisioning.desktop.kt).

Permite a la UI de `commonMain` ([[Gestión de claves y secretos UI]]) aprovisionar
la clave sin tocar el almacén nativo:

- Genera/reutiliza la clave **en el alias que el usuario elige** (no un alias fijo)
  y expone su línea `authorized_keys` para enrolarla en el servidor.
- La clave privada es no exportable y nunca sale del hardware; el motor la usa
  para firmar vía el [[Signer SSH delegado]] (`SshCredentials.HardwareKey`).
- En escritorio la clave vive en el TPM 2.0 (`DesktopHardwareKeys`, que
  `main` instala al arrancar): CNG en Windows, `/dev/tpmrm0` en Linux. Si no
  hay un TPM usable, `hardwareKeyUnavailableReason()` dice por qué (sin TPM,
  o en Linux sin acceso por no estar en el grupo `tss`) y el editor desactiva
  la acción mostrando ese motivo.

Gobernado por [[ADR-0005 Autenticación SSH y verificación de host]] y
[[ADR-0033 Clave no exportable en el TPM del escritorio]].
