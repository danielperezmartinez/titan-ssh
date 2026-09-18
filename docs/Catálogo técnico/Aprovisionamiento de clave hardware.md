---
Nombre: "Aprovisionamiento de clave hardware"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/ssh/HardwareKeyProvisioning.kt"
Entrada pública: "im.gar.titanssh.ssh"
Resumen: "Seam expect/actual para generar/reutilizar desde commonMain una clave no exportable en el almacén de claves del SO y obtener su línea authorized_keys (ADR-0005). isHardwareKeyProvisioningSupported() decide si la acción está disponible; ensureHardwareKey(alias) genera bajo el alias elegido y devuelve la línea OpenSSH (o lanza HardwareKeyUnsupported). Android: actual sobre AndroidHardwareKeys (EC P-256 en Android Keystore/StrongBox, off-main). Escritorio v1: no soportado (sin clave no exportable portable). La privada nunca sale del hardware; el editor de host lo usa para 'Generar / mostrar clave'."
Última modificación: 2026-09-18T17:05:00+02:00
---

# Aprovisionamiento de clave hardware

Fuente de verdad: el seam en
[HardwareKeyProvisioning.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/ssh/HardwareKeyProvisioning.kt)
y su `actual` de Android en
[HardwareKeyProvisioning.android.kt](../../shared/src/androidMain/kotlin/im/gar/titanssh/ssh/HardwareKeyProvisioning.android.kt)
(sobre [[Signer SSH delegado]] / `AndroidHardwareKeys`) y el `actual` de escritorio
[HardwareKeyProvisioning.desktop.kt](../../shared/src/desktopMain/kotlin/im/gar/titanssh/ssh/HardwareKeyProvisioning.desktop.kt).

Permite a la UI de `commonMain` ([[Gestión de claves y secretos UI]]) aprovisionar
la clave sin tocar el almacén nativo:

- Genera/reutiliza la clave **en el alias que el usuario elige** (no un alias fijo)
  y expone su línea `authorized_keys` para enrolarla en el servidor.
- La clave privada es no exportable y nunca sale del hardware; el motor la usa
  para firmar vía el [[Signer SSH delegado]] (`SshCredentials.HardwareKey`).
- En escritorio v1 no hay clave hardware no exportable portable: el seam informa
  que no está soportado y el editor deshabilita la acción con una nota.

Gobernado por [[ADR-0005 Autenticación SSH y verificación de host]].
