---
Nombre: "KnownHostsVerifier"
Tipo: "Servicio"
Área: "Seguridad"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/ssh/KnownHosts.kt"
Entrada pública: "im.gar.titanssh.ssh"
Resumen: "Verificación de host key TOFU (ADR-0005) enchufable en el HostKeyVerifier del motor SSH. KnownHostsVerifier(store, prompt): en primer contacto pide confirmación (HostTrustPrompt) y persiste; casa en reconexión; rechaza una clave cambiada como MITM. KnownHostsStore persiste (InMemoryKnownHostsStore; FileKnownHostsStore en formato OpenSSH known_hosts). Verificada headless y contra host real."
Última modificación: 2026-09-17T20:55:00+02:00
---

# KnownHostsVerifier

Después de descubrir esta pieza, consulta su contrato e implementación en
[KnownHosts.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/ssh/KnownHosts.kt)
(`KnownHostsVerifier`, `KnownHostsStore`, `HostTrustPrompt`,
`InMemoryKnownHostsStore`) y el store en fichero
[FileKnownHostsStore.kt](../../shared/src/jvmSharedMain/kotlin/im/gar/titanssh/ssh/FileKnownHostsStore.kt),
que son la fuente de verdad.

Se conecta al motor a través de [[SshConnector]] (parámetro `hostKeyVerifier`).
Gobernada por [[ADR-0005 Autenticación SSH y verificación de host]] y detallada en
[[Autenticación SSH signer en hardware y verificación de host]].
