---
Nombre: "AgentInstaller"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentInstaller.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Instala el agente de nivel 3 (titan-agent) en el destino de una SshSession y dice cómo lanzarlo, en Windows y en cualquier Unix soportado (ADR-0009 §7). Detecta el SO por la ruta canónica de SFTP (/C:/... = Windows), la arquitectura y la shell del exec (una sonda de cmd en Windows, uname en Unix) y lo instala versionado en espacio de usuario (%LOCALAPPDATA%\titan-ssh o ~/.local/share/titan-ssh). Sube por SFTP a un temporal, chmod 0700 en Unix y renombra; sin SFTP, en Unix, usa exec + head -c. Verifica el SHA-256 con la herramienta del destino (sha256sum, shasum, sha256, certutil) o releyendo por SFTP, no resube si ya coincide y borra los binarios de otras versiones. Devuelve Installed(AgentLaunch), Unsupported o Failed. agentDeployer() lo envuelve como AgentDeployer (null = degradar al nivel 2/1). Los binarios salen de AgentBinaries (seis empaquetados) o de AgentDownloader (el resto, del GitHub Release, contra el SHA-256 fijado en /agent/SHA256SUMS, ADR-0010). Las funciones puras (mapeos, rutas, comillas, parseo de hashes) están en AgentInstall.kt, en commonMain."
Última modificación: 2026-09-27T10:40:00+02:00
---

# AgentInstaller

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentInstaller.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentInstaller.kt),
las funciones puras de
[AgentInstall.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentInstall.kt)
(`AgentDeployer`, `AgentTarget`, `RemoteShell`, `AgentLaunch`) y la carga y
descarga de binarios en
[AgentDeployerFactory.jvmShared.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentDeployerFactory.jvmShared.kt)
(`AgentBinaries`, `AgentDownloader`). Cobertura: `AgentInstallTest`,
`AgentInstallerTest` (host falso), `AgentDownloaderTest`,
`AgentBinariesResourceTest` y `AgentInstallerIntegrationTest` (host real,
opt-in, Unix o Windows).

`createAgentDeployer()` (actuals de Android y escritorio, que solo eligen el
directorio de caché de las descargas) es el punto de entrada que cablea
`AppShell`. `SessionTab` llama a `ensureInstalled` en cada conexión y, si hay
`AgentLaunch`, conduce la sesión con [[AgentTransport]].

Piezas relacionadas:

- [[SshConnector]] — `openSftp()` para la subida y `exec` para las sondas.
- [[AgentTransport]] — lanza el agente con el `AgentLaunch` devuelto.
- [[Instalación del agente en destinos Windows y multi-SO]] — la tarea, los
  hallazgos y la verificación.
- [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] — qué se empaqueta
  y de dónde se descarga el resto.
