---
Nombre: "SecretProvisioner"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Custodia de credenciales"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/secret/SecretProvisioner.kt"
Entrada pública: "im.gar.titanssh.secret"
Resumen: "Lado de escritura del SecretStore (ADR-0001): materializa en el almacén el material de autenticación que el HostAuth solo referencia. savePassword(ref, password) guarda una contraseña; importPrivateKey(keyRef, pem, passphraseRef?, passphrase?) importa una clave privada PEM existente (+ passphrase opcional) validando que parezca PEM; has/remove consultan y borran. Codifica a UTF-8 y borra (wipe) la copia transitoria tras put. Es el contraparte de escritura del CredentialResolver (lectura); el documento de config sigue guardando solo referencias. Generar un par nuevo in-app queda como stretch pendiente."
Última modificación: 2026-09-18T17:05:00+02:00
---

# SecretProvisioner

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[SecretProvisioner.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/secret/SecretProvisioner.kt).

Es el puente de **escritura** entre la UI del editor de host y el almacén, usado
por [[Gestión de claves y secretos UI]]:

- Escribe en [[SecretStore]]; nunca deja el secreto en el documento de config
  ([[ConfigStore]] / [[Modelo de configuración]]).
- Es simétrico del [[CredentialResolver]] (lectura en tiempo de conexión): lo que
  este servicio guarda es exactamente lo que aquel materializa.
- La clave hardware no exportable **no** pasa por aquí: se aprovisiona en el
  almacén del SO vía [[Aprovisionamiento de clave hardware]] y se referencia por
  alias (ADR-0005).

Gobernado por [[ADR-0001 Credenciales en almacén nativo del SO]] y
[[ADR-0005 Autenticación SSH y verificación de host]]; cobertura en
`SecretProvisionerTest`.
