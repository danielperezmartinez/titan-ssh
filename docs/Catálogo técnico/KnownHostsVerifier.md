---
Nombre: "KnownHostsVerifier"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/KnownHosts.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: 'Verificación de host key TOFU (ADR-0005) enchufable en el HostKeyVerifier del motor SSH. La confianza es por host y puerto, sea cual sea el tipo de clave. KnownHostsVerifier(store, acceptNewHosts, onRejected, prompt): en primer contacto pide confirmación (HostTrustPrompt) y persiste, salvo con acceptNewHosts = false (política STRICT), que rechaza sin preguntar; casa en reconexión; cualquier otra clave de un host conocido se rechaza como cambiada, sin preguntar. Cada rechazo llega a onRejected como HostKeyRejection (Changed, NotTrusted o Declined). knownKeyTypes da los tipos guardados para que el motor los pida primero. KnownHostsStore persiste (InMemoryKnownHostsStore; FileKnownHostsStore en formato OpenSSH known_hosts) y replace sustituye a propósito las claves de un host. Verificada headless y contra host real.'
Última modificación: 2026-10-02T01:38:01+02:00
---

# KnownHostsVerifier

Después de descubrir esta pieza, consulta su contrato e implementación en
[KnownHosts.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/KnownHosts.kt)
(`KnownHostsVerifier`, `KnownHostsStore`, `HostTrustPrompt`,
`HostKeyRejection`, `InMemoryKnownHostsStore`) y el store en fichero
[FileKnownHostsStore.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/FileKnownHostsStore.kt),
que son la fuente de verdad.

Se conecta al motor a través de [[SshConnector]] (parámetro `hostKeyVerifier`).
Gobernada por [[ADR-0005 Autenticación SSH y verificación de host]] y detallada en
[[Autenticación SSH signer en hardware y verificación de host]].
