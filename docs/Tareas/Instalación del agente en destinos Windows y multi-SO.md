---
Nombre: 'Instalación del agente en destinos Windows y multi-SO'
Estado: 'Pendiente'
Resumen: 'Subtarea 5 de ADR-0009 (§6 y §7), lado cliente: que AgentInstaller instale y lance el agente en cualquier destino soportado. Hoy es solo Unix (uname, sha256sum, head -c, chmod, mv y ~ por el canal exec; destinos linux amd64/arm64 y darwin arm64). Cambios: añadir SFTP a SshSession (sshj SFTPClient) y subir por SFTP en todos los SO (exec+head queda como alternativa en Unix sin SFTP); detectar el SO por la ruta canónica de SFTP (/C:/... = Windows) y la arquitectura con una sonda por SO; checksum calculado en el cliente releyendo por SFTP; rutas por SO y sufijo .exe; comando exec con las comillas de la shell del destino (cmd o PowerShell); ampliar agentTargets y la detección a todos los destinos de la ADR; y empaquetar según ADR-0010 (seis destinos principales en la app, el resto bajo demanda con SHA-256 fijado; origen de descarga pendiente). El método de prueba en Windows está por decidir con el usuario.'
Decisiones: 'Implementa §6 y §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y el empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]. Vuelve a la subida por SFTP que preveía §5 de [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (la implementación de [[titan-agent distribución multi-arch e instalación]] la sustituyó por exec+head). Contexto común en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-24T14:00:00+02:00
---

# Instalación del agente en destinos Windows y multi-SO

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa §6 y §7 de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. Se puede avanzar
en paralelo a las subtareas del agente; la prueba de punta a punta en Windows
necesita [[titan-agent daemon en Windows]].

## Código actual (lo que hay que cambiar)

- `shared/src/commonMain/.../terminal/AgentInstall.kt`:
  - `AgentDeployer` (`fun interface`, `ensureInstalled(session): String?`,
    null = degradar).
  - `AgentTarget(os, arch)` con `slug = "$os-$arch"`.
  - `AgentInstall.DEFAULT_BASE_DIR = "~/.local/share/titan-ssh"`.
  - `parseUname`: solo `linux`/`darwin` y `x86_64|amd64` / `aarch64|arm64`.
  - `remotePath(baseDir, version, target)` = `"$baseDir/agent-$version-$slug"`
    (sin comillas, para que `~` se expanda).
  - `parseSha256` (salida de `sha256sum`).
- `shared/src/jvmSharedMain/.../terminal/AgentInstaller.kt`: todo por
  `session.exec`: `uname -s; uname -m`, `sha256sum $path`,
  `mkdir -p … && head -c <n> > tmp && chmod 700 && mv && sha256sum`.
  Resultado `Installed / Unsupported / Failed`.
- `AgentDeployerFactory.jvmShared.kt`: `AgentBinaries` carga
  `/agent/titan-agent-<slug>` del classpath; `VERSION = BuildInfo.VERSION`
  (versión única de la app desde 2026-09-24, ver
  [[Versionado único desde tag de git]]);
  `createAgentDeployer()`.
- `AgentTransport.kt` línea 49: `session.exec("$agentPath --session $safeId")`.
- `shared/build.gradle.kts`: `agentTargets = listOf("linux" to "amd64", "linux" to "arm64", "darwin" to "arm64")`;
  `buildAgentBinaries` compila con `go build -trimpath -ldflags "-s -w"` a
  `titan-agent-$os-$arch` (sin `.exe`, sin `CGO_ENABLED=0` explícito).
- `SshSession` (commonMain, `ssh/SshSession.kt`) solo tiene `openShell`, `exec`
  y `close`: **no hay SFTP**. sshj 0.39.0 incluye `SFTPClient`.
- Tests: `AgentInstallTest` (unitario), `AgentBinariesResourceTest` (comprueba
  cabecera ELF), `AgentInstallerIntegrationTest` (opt-in, host real con
  `-PtitanAgentBin`).

## Cambios

### 1. SFTP en el motor SSH

Añadir a `SshSession` una superficie SFTP mínima (subir fichero, renombrar,
`stat`, leer, `setattr` de permisos, `canonicalize`), implementada en
`SshjSession` sobre `SFTPClient`. Actualizar la entrada del catálogo técnico
del motor SSH **en el mismo cambio** (regla del catálogo; ver
`Catálogo técnico/SshConnector.md`). El `sshd` de Windows tiene
`Subsystem sftp sftp-server.exe`.

### 2. Detección de SO y arquitectura

- Primero `canonicalize(".")` por SFTP: en Windows devuelve `/C:/Users/<u>`
  (letra de unidad), en Unix `/home/<u>` o similar. Sirve de paso como ruta
  absoluta del home (SFTP no expande `~`).
- **Windows**: `cmd /c echo %PROCESSOR_ARCHITECTURE% %PROCESSOR_ARCHITEW6432%`
  (funciona aunque la shell por defecto sea PowerShell, porque se invoca `cmd`
  explícitamente). `AMD64` → `amd64`, `ARM64` → `arm64`; `x86` con
  `PROCESSOR_ARCHITEW6432` = `AMD64` → proceso de 32 bits en un Windows de 64:
  usar `amd64`.
- **Unix**: `uname -s; uname -m`, con el mapeo ampliado: `Linux`, `Darwin`,
  `FreeBSD`; `x86_64|amd64` → `amd64`, `aarch64|arm64` → `arm64`,
  `armv7l|armv7*` → `arm`, `i386|i686` → `386`, `riscv64`, `ppc64le`, `s390x`.
- Sin SFTP (subsistema desactivado): usar `uname` directamente y asumir Unix.

### 3. Rutas de instalación

- Unix: `<home>/.local/share/titan-ssh/agent-<ver>-<os>-<arch>` (ruta absoluta
  a partir de `canonicalize`).
- Windows: `%LOCALAPPDATA%\titan-ssh\agent-<ver>-windows-<arch>.exe` (el `.exe`
  es obligatorio para ejecutarlo). Obtener `%LOCALAPPDATA%` con
  `cmd /c echo %LOCALAPPDATA%` (puede estar redirigido; no suponer
  `AppData\Local`). En SFTP la ruta se escribe `/C:/Users/<u>/AppData/Local/...`.

### 4. Subida e integridad

- Subir a un temporal en el mismo directorio y renombrar al final (atómico).
- Unix: `setattr` a `0700`. Windows: no aplica (el perfil es privado).
- **Checksum en el cliente**: releer el fichero remoto por SFTP y calcular el
  SHA-256 localmente. Evita depender de `sha256sum` (Unix) o
  `certutil`/`Get-FileHash` (Windows). Idempotencia: si el fichero existe y su
  SHA-256 coincide, no subir.
- Sin SFTP en un destino Unix: conservar el camino actual (`head -c` +
  `sha256sum`) como alternativa.

### 5. Comando `exec` según la shell del destino

La shell del `exec` es la `DefaultShell` de OpenSSH (en Windows, `cmd.exe` por
defecto; puede ser PowerShell). Una ruta con espacios (nombre de usuario con
espacios) necesita comillas distintas:
- cmd: `"C:\...\agent.exe" --session <id>`.
- PowerShell: `& 'C:\...\agent.exe' --session <id>`.
- Sonda propuesta: `echo %COMSPEC%`. En cmd imprime la ruta de `cmd.exe`; en
  PowerShell imprime `%COMSPEC%` literal.
- Unix: como hoy (`sh`).
Llevar la ruta y la shell detectadas del instalador a `AgentTransport` (hoy
recibe solo `agentPath`).

### 6. Destinos compilados y empaquetado

- `agentTargets` (y la detección) pasa a la lista de §6 de la ADR:
  `linux/{amd64, arm64, arm (GOARM=7), 386, riscv64, ppc64le, s390x}`,
  `darwin/{amd64, arm64}`, `windows/{amd64, arm64}`, `freebsd/{amd64, arm64}`.
  Añadir `CGO_ENABLED=0` explícito y el sufijo `.exe` en Windows.
- `AgentBinariesResourceTest` comprueba ELF: ampliarlo a Mach-O (macOS) y PE
  (`MZ`, Windows).
- **Empaquetado decidido** por el usuario el 2026-09-23 y recogido en
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] (`Propuesta`):
  - empaquetar en la app linux amd64/arm64, darwin amd64/arm64 y windows
    amd64/arm64;
  - descargar el resto bajo demanda, con el SHA-256 de cada binario fijado en la
    app en tiempo de compilación, caché local por versión y degradación con
    diagnóstico si falla.
  - **Pendiente**: el origen de la descarga (releases de GitHub exigen
    repositorio público o credenciales). La descarga bajo demanda no se puede
    implementar hasta cerrarlo; el resto de esta subtarea, sí.
- Después de cambiar los destinos, [[Verificar empaquetado del agente en APK Android]]
  debe comprobar la nueva lista (usar la skill `android-cli`).

## Tests

- Unitarios: mapeo de `uname` ampliado, mapeo de `PROCESSOR_ARCHITECTURE`,
  detección por ruta canónica, construcción de rutas y del comando `exec` para
  cmd/PowerShell/sh (incluidas rutas con espacios), verificación del SHA-256
  fijado para los binarios descargados.
- Integración (opt-in): `AgentInstallerIntegrationTest` contra Linux
  (`<host-de-pruebas>` si está disponible).
- **Pruebas en Windows: método por decidir.** El usuario quiere explorar otras
  opciones además del `sshd` local con un usuario estándar temporal (el que se
  usó en [[Experimento supervivencia de procesos en Win32-OpenSSH]]). Se decide
  **al llegar a este punto**, preguntándole antes de montar nada. Ver
  [[Nivel 3 portable a todos los destinos]].

## Criterios de finalización

- El instalador detecta, sube, verifica y lanza el agente en Linux y Windows
  (probado de punta a punta, en Windows con el método que se decida); macOS y
  FreeBSD cubiertos por tests unitarios de detección y rutas (sin máquina para
  probarlos).
- Empaquetado según [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]:
  seis destinos empaquetados; descarga bajo demanda implementada una vez decidido
  el origen.
- Catálogo técnico actualizado con la superficie SFTP.
