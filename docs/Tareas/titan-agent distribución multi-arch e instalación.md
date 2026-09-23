---
Nombre: titan-agent distribución multi-arch e instalación
Estado: Hecha
Resumen: 'ENTREGADO. Instalación y empaquetado del binario titan-agent. AgentInstaller (jvmShared) detecta uname -s/-m, elige el binario, y si el SHA-256 del fichero versionado remoto no coincide ya, lo sube por el canal EXEC (head -c <size>, sin SFTP), hace chmod 0700 y verifica el checksum; idempotente; ruta versionada ~/.local/share/titan-ssh/agent-<ver>-<os>-<arch> en espacio de usuario (sin root). Empaquetado: el task Gradle buildAgentBinaries cross-compila el agente (linux amd64/arm64, darwin arm64) a recursos JVM /agent/ (best-effort: si no hay Go, no empaqueta y AGENT degrada); AgentBinaries los carga por classloader (app de escritorio y Android). Verificado: unit (AgentInstallTest, AgentBinariesResourceTest con chequeo ELF real), y host real (AgentInstallerIntegrationTest: sube, checksum, idempotente, ejecuta).'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (§5). Reutiliza el canal exec de [[Nivel 3 canal exec en el motor SSH]] en vez de SFTP (menos superficie). Binarios cross-compilados por Gradle a recursos JVM (no comiteados; en build/), cargados por classloader — mismo mecanismo para escritorio y Android.'
Bloqueada: []
Fecha de creación: 2026-09-19T18:25:00+02:00
Última modificación: 2026-09-19T20:15:00+02:00
---

# titan-agent: distribución multi-arch e instalación

## Objetivo

Que el cliente pueda **poner el agente correcto en el destino** de forma
automática, íntegra y sin privilegios.

## Criterios de finalización

- Detección del destino (`uname -s`, `uname -m`) para elegir binario. ✅
  (`AgentInstall.parseUname` + `AgentInstaller.detectTarget`.)
- Subida a una ruta **versionada** en espacio de usuario, `0700`, idempotente por
  checksum. ✅ Se hace por el **canal exec** (`head -c <size>`), no SFTP: reutiliza
  [[SshConnector]]`.exec` y evita nueva superficie.
- **Verificación de integridad** SHA-256 tras subir; si no coincide → `Failed`
  (el llamador degrada al nivel 2/1). ✅
- Pipeline de build multi-arch que **empaquete** los binarios como recursos de la
  app y un `binaryFor(target)` que los sirva. ✅ Task Gradle `buildAgentBinaries`
  (cross-compila a `build/generated/agentBinaries/agent/`, cableado como resources
  de `jvmSharedMain` y dependido por los tasks de resources/APK); `AgentBinaries`
  (jvmShared) los carga por classloader; `createAgentDeployer()` (expect/actual)
  los sirve. Best-effort: sin Go, no empaqueta y AGENT degrada.
- Vías secundarias (instalación manual, bootstrap). ⬜ Pendiente (opcional).

## Entregado

- `AgentInstall` (`commonMain/terminal`): helpers puros — `parseUname`,
  `remotePath`, `parseSha256`. Unit test `AgentInstallTest`.
- `AgentInstaller` (`jvmShared/terminal`): `detectTarget()` y
  `ensureInstalled(binaryFor)` → `Installed(path,target,uploaded)` /
  `Unsupported` / `Failed`. Sube por exec+`head -c`, verifica SHA-256, idempotente.

## Verificación

- **Headless** (`AgentInstallTest`): mapeo uname (Linux x86_64→linux-amd64,
  aarch64→arm64, Darwin arm64), rechazo de OS/arch no soportados, ruta versionada,
  parseo de `sha256sum` (incl. fichero ausente). Verde.
- **Empaquetado** (`AgentBinariesResourceTest`): el task `buildAgentBinaries`
  produce los 3 binarios (ELF linux amd64/arm64 estáticos + Mach-O darwin arm64) y
  el linux/amd64 se carga desde el classpath con magia ELF verificada; el deployer
  se construye. Verde.
- **Host real** ([[ssh-test-host]], `AgentInstallerIntegrationTest`, opt-in con
  `TITAN_AGENT_BIN`): `tests=1 failures=0` — subió el binario linux/amd64
  (`uploaded=true`), la 2.ª llamada fue **idempotente** (`uploaded=false`), el
  binario instalado respondió `titan-agent` a `--version`, y se limpió el host.

## Notas

- Solo espacio de usuario, nunca root (ADR-0008 §6, seguridad).
- El empaquetado de binarios está atado al empaquetado de escritorio del proyecto
  (formatos aún por decidir), por eso se difiere.
