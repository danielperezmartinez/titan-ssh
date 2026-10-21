---
Nombre: "Instalador de clave en el host"
Tipo: "Servicio"
Área: "Conexión SSH"
Feature: "Autenticación"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/keyinstall/KeyInstaller.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.keyinstall"
Resumen: "KeyInstaller.install(host, jumps, password, publicLine, keyAuth, askHostKey, onStep) instala una línea pública en el host como ssh-copy-id y comprueba que entra (ADR-0030): conecta con una contraseña de un solo uso por los saltos del host y con la verificación TOFU de siempre (HostKeyQuestion), detecta Unix o Windows, escribe con el script POSIX de KeyInstallScripts (sh -s, umask 077, sin duplicar, restorecon) o por SFTP (cuentas sin shell; Windows, con administrators_authorized_keys e icacls para administradores) y vuelve a conectar solo con la clave. Devuelve KeyInstallResult: Installed (ya estaba, sistema, fichero, avisos) o Failed (paso KeyInstallStep, problema del catálogo cerrado KeyInstallProblem con título y consejo, detalle). No cambia la configuración: el editor de host guarda el host con la clave solo tras Installed. La UI es KeyInstallPanel, solo en la canary."
Última modificación: 2026-10-21T19:30:00+02:00
---

# Instalador de clave en el host

Fuente de verdad:
[KeyInstaller.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/keyinstall/KeyInstaller.kt),
el catálogo de errores en
[KeyInstallProblem.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/keyinstall/KeyInstallProblem.kt)
y lo que corre en el host en
[KeyInstallScripts.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/keyinstall/KeyInstallScripts.kt).

- Se apoya en [[SshConnector]] (con `keyboard-interactive` y los detalles de
  `SshAuthFailed`), [[KnownHostsVerifier]] y [[CredentialResolver]] para las
  credenciales de los saltos y de la comprobación con la clave.
- La clave la prepara el consumidor: [[Aprovisionamiento de clave hardware]] o
  `SecretProvisioner.ensureSoftwareKey` ([[SecretProvisioner]]).
- La contraseña se copia en cada intento y se borra; el instalador no la guarda.

Gobernado por
[[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]];
tarea [[Instalar la clave pública en el host desde la app]]. Cobertura en
`KeyInstallScriptsTest` (el script con `/bin/sh` real), `KeyInstallerTest`
(servidores falsos, Windows y saltos) y `KeyInstallIntegrationTest` (opt-in,
contenedores de `tools/test-sshd/key-install-scenarios.sh`).
