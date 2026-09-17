---
Nombre: "SshConnector"
Tipo: "Contrato"
Área: "Conexión SSH"
Feature: "Conexión"
Estado: "En revisión"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/ssh/SshSession.kt"
Entrada pública: "im.gar.titanssh.ssh"
Resumen: "Primitiva de conexión SSH. SshConnector.connect(endpoint, credentials, hostKeyVerifier, keepAlive) abre una SshSession autenticada; SshSession expone estado observable y openShell(); SshShell da flujo de salida, envío de entrada y resize. Verificador de host inyectable (fingerprint SHA256) y credenciales password/clave desde el SecretStore. Impl sshj en jvmShared; se obtiene con createSshConnector(). En revisión: falta verificar el handshake real contra un host."
Última modificación: 2026-09-17T20:15:00+02:00
---

# SshConnector

Después de descubrir esta pieza en el catálogo, consulta su contrato en
[SshSession.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/ssh/SshSession.kt)
(interfaces `SshConnector` / `SshSession` / `SshShell`) y la implementación sshj en
[SshjConnector.kt](../../shared/src/jvmSharedMain/kotlin/im/gar/titanssh/ssh/SshjConnector.kt),
que son la fuente de verdad.

`Estado: En revisión` hasta validar el handshake real contra un servidor SSH (ver
[[Motor de conexión SSH]]). Evítese depender de detalles finos del contrato hasta
estabilizarla.

Piezas relacionadas:

- [[SecretStore]] — custodia las credenciales que alimentan `SshCredentials`.
- La verificación `known_hosts` (TOFU) y el signer en hardware se enchufan aquí
  desde [[Autenticación SSH signer en hardware y verificación de host]].
