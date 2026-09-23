---
Nombre: "SshConnector"
Tipo: "Contrato"
Área: "Conexión SSH"
Feature: "Conexión"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshSession.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: "Primitiva de conexión SSH. SshConnector.connect(endpoint, credentials, hostKeyVerifier, keepAlive) abre una SshSession autenticada; SshSession expone estado observable, openShell() y exec(command); SshShell da flujo de salida, envío de entrada y resize; SshExecChannel corre un comando sin PTY (stdout/stderr/stdin crudos + exit status), transporte del agente de resiliencia nivel 3 (ADR-0008). Verificador de host inyectable (fingerprint SHA256) y credenciales password/clave desde el SecretStore. Impl sshj en jvmShared; se obtiene con createSshConnector(). Verificada contra un host real (handshake, auth por clave, shell PTY); el canal exec tiene test de integración opt-in."
Última modificación: 2026-09-24T12:00:00+02:00
---

# SshConnector

Después de descubrir esta pieza en el catálogo, consulta su contrato en
[SshSession.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshSession.kt)
(interfaces `SshConnector` / `SshSession` / `SshShell` / `SshExecChannel`) y la implementación sshj en
[SshjConnector.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshjConnector.kt),
que son la fuente de verdad.

Verificada de punta a punta contra un host real (ver [[Motor de conexión SSH]]).

Piezas relacionadas:

- [[SecretStore]] — custodia las credenciales que alimentan `SshCredentials`.
- La verificación `known_hosts` (TOFU) y el signer en hardware se enchufan aquí
  desde [[Autenticación SSH signer en hardware y verificación de host]].
- `SshExecChannel` es el transporte del nivel 3 de resiliencia: sobre él corre el
  protocolo por tramas [[AgentProtocol]] (ver
  [[Nivel 3 canal exec en el motor SSH]] y
  [[ADR-0008 Diseño del agente de resiliencia nivel 3]]).
