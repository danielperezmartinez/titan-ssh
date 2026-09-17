---
Nombre: "SshConnector"
Tipo: "Contrato"
Área: "Conexión SSH"
Feature: "Conexión"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/ssh/SshSession.kt"
Entrada pública: "im.gar.titanssh.ssh"
Resumen: "Primitiva de conexión SSH. SshConnector.connect(endpoint, credentials, hostKeyVerifier, keepAlive) abre una SshSession autenticada; SshSession expone estado observable y openShell(); SshShell da flujo de salida, envío de entrada y resize. Verificador de host inyectable (fingerprint SHA256) y credenciales password/clave desde el SecretStore. Impl sshj en jvmShared; se obtiene con createSshConnector(). Verificada contra un host real (handshake, auth por clave, shell PTY)."
Última modificación: 2026-09-17T20:30:00+02:00
---

# SshConnector

Después de descubrir esta pieza en el catálogo, consulta su contrato en
[SshSession.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/ssh/SshSession.kt)
(interfaces `SshConnector` / `SshSession` / `SshShell`) y la implementación sshj en
[SshjConnector.kt](../../shared/src/jvmSharedMain/kotlin/im/gar/titanssh/ssh/SshjConnector.kt),
que son la fuente de verdad.

Verificada de punta a punta contra un host real (ver [[Motor de conexión SSH]]).

Piezas relacionadas:

- [[SecretStore]] — custodia las credenciales que alimentan `SshCredentials`.
- La verificación `known_hosts` (TOFU) y el signer en hardware se enchufan aquí
  desde [[Autenticación SSH signer en hardware y verificación de host]].
