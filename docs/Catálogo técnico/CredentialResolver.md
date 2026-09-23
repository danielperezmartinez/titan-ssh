---
Nombre: "CredentialResolver"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Custodia de credenciales"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/CredentialResolver.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Materializa las SshCredentials vivas a partir del HostAuth (solo referencias) leyendo el material del SecretStore en tiempo de conexión (ADR-0001). Password y SoftwareKey (con passphrase opcional) se leen del almacén; la HardwareKey NO se materializa como bytes: devuelve SshCredentials.HardwareKey(alias) y el motor resuelve el signer delegado en el almacén del SO (ADR-0005). Lanza MissingSecret si falta una referencia. El llamante (SessionTab) borra el material tras el handshake."
Última modificación: 2026-09-24T12:00:00+02:00
---

# CredentialResolver

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[CredentialResolver.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/CredentialResolver.kt).

Es el puente entre la configuración (referencias) y el motor (credenciales vivas),
usado por el flujo de lanzamiento de [[SessionManager]]:

- Lee el material de [[SecretStore]]; nunca hay secretos en el documento de config
  ([[ConfigStore]] / [[Modelo de configuración]]).
- La clave hardware no pasa por bytes: solo su alias, resuelto a un signer
  delegado por el motor ([[Signer SSH delegado]]).
- El borrado del material lo hace `SessionTab` tras conectar; el motor no lo
  retiene.

Gobernado por [[ADR-0001 Credenciales en almacén nativo del SO]] y
[[ADR-0005 Autenticación SSH y verificación de host]]; cobertura en
`CredentialResolverTest`.
