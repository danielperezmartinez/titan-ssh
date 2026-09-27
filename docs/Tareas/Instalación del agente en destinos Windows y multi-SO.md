---
Nombre: 'Instalación del agente en destinos Windows y multi-SO'
Estado: 'En curso'
Resumen: 'Subtarea 5 de ADR-0009 (§6 y §7), lado cliente: que AgentInstaller instale y lance el agente en cualquier destino soportado. Hecho el 2026-09-27: SFTP en SshSession; detección por la ruta canónica de SFTP (/C:/... = Windows), con una sonda de cmd para arquitectura, %LOCALAPPDATA% y shell (cmd o PowerShell), y uname ampliado a los 13 destinos en Unix; subida a un temporal, chmod 0700 y renombrado; SHA-256 con la herramienta del destino (sha256sum, shasum, sha256, certutil) o releyendo por SFTP; exec+head como alternativa en Unix sin SFTP; borrado de los binarios de otras versiones; comando con las comillas de sh, cmd o PowerShell. Empaquetado según ADR-0010: seis destinos en la app, los otros siete publicados en el GitHub Release y descargados bajo demanda contra el SHA-256 fijado en la app. Probado de punta a punta en Windows (usuario estándar) y Linux (amd64, y arm y riscv64 emulados). Queda En curso hasta ver el pipeline nuevo en la siguiente pre-release.'
Decisiones: 'Implementa §6 y §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y el empaquetado de [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]. Vuelve a la subida por SFTP que preveía §5 de [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (la implementación de [[titan-agent distribución multi-arch e instalación]] la sustituyó por exec+head). Contexto común en [[Nivel 3 portable a todos los destinos]]. La limpieza de binarios antiguos llega aquí desde [[titan-agent instancia única y directorio de estado]] (2026-09-27). Decisiones del usuario del 2026-09-27: el origen de descarga es GitHub Releases, con los 13 binarios publicados en cada Release, y la prueba en Windows reutiliza la cuenta estándar temporal del paso 9.4.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-27T11:15:00+02:00
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
  Es el mismo directorio que el de estado del agente (`agent.lock` y
  `agent.json`), así que el instalador no debe borrar nada que no sea
  `agent-*`.
- **Binarios de versiones anteriores** (decidido el 2026-09-27 en
  [[titan-agent instancia única y directorio de estado]]): tras instalar y
  verificar el de la versión actual, borrar los `agent-*` de otras versiones.
  - En Unix es seguro aunque un daemon viejo siga ejecutándose desde uno de
    ellos: el inodo vive hasta que el proceso termina.
  - En Windows no se puede borrar un `.exe` en ejecución: si el borrado falla,
    se ignora y se reintenta en la siguiente instalación.
  - El daemon viejo sigue sirviendo sus sesiones, porque el front nuevo lo
    encuentra por `agent.json` y el protocolo es compatible. Así las sesiones
    sobreviven a una actualización de la app.

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

## Resultado (2026-09-27)

Decisiones del usuario al empezar:
- **Origen de descarga**: GitHub Releases. Cada Release publica los **13**
  binarios del agente (la lista de §6 de la ADR-0009 son 13, no unos 15), no
  solo los siete que se descargan: así también sirven para instalarlo a mano.
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] pasa a `Aceptada`.
- **Prueba en Windows**: la cuenta estándar temporal del paso 9.4, con una
  clave nueva que autorizó el usuario (la de 9.4 ya se había borrado).

### Código

- `SshSession.openSftp()` (null si el servidor no tiene subsistema `sftp`) y
  `SshSftp`, implementado en `SshjSftp` sobre `SFTPClient`. Ver
  [[SshConnector]].
- `AgentInstall.kt` (commonMain, funciones puras): `parseUname` para los 13
  destinos, `parseWindowsProbe`, `isWindowsSftpPath`/`windowsToSftpPath`,
  `binaryName` (`agent-<versión>-<os>-<arch>[.exe]`), `isStaleBinary`,
  `command` y `hashCommand` por shell, y `parseSha256`, que entiende
  `sha256sum`, `shasum`, `sha256 -q` y `certutil`. `RemoteShell` y
  `AgentLaunch`: el `AgentDeployer` devuelve ahora un `AgentLaunch` (ruta y
  shell), y `AgentTransport` lo usa para el comando.
- `AgentInstaller` (jvmShared): el flujo de §2–§4 de esta nota. Ver
  [[AgentInstaller]].
  - Windows: una sola sonda sin comillas,
    `echo %PROCESSOR_ARCHITECTURE% %PROCESSOR_ARCHITEW6432% %LOCALAPPDATA%`.
    Si cmd no la expande, la shell es PowerShell y se repite con `cmd /c`.
    Se instala en `%LOCALAPPDATA%\titan-ssh`, que también es el directorio de
    estado del agente.
  - Unix: `<home>/.local/share/titan-ssh`, con el home que da
    `canonicalize(".")`. Sin SFTP, `~/.local/share/titan-ssh` y `head -c`.
  - Idempotencia: primero la herramienta de hash del destino, que es barata y
    mantiene rápidas las reconexiones. Si no responde y el tamaño coincide, se
    relee el fichero por SFTP.
  - La limpieza borra los `agent-*` de otras versiones, también las subidas a
    medias, y nunca `agent.lock` ni `agent.json`. En Windows un `.exe` en uso
    no se puede borrar: se ignora.
- `AgentDeployerFactory`: `AgentBinaries` carga el binario empaquetado y el
  manifiesto `/agent/SHA256SUMS`. `AgentDownloader` descarga de
  `releases/download/v<versión>/titan-agent-<versión>-<os>-<arch>[.exe]`,
  exige el SHA-256 fijado y guarda en caché por versión. Una build de
  desarrollo no descarga, porque no tiene Release.
  - La caché está en `cacheDir/agent` en Android y en
    `%LOCALAPPDATA%\titan-ssh\cache\agent` o `~/.cache/titan-ssh/agent` en
    escritorio (actuals en `androidMain`/`desktopMain`).
- `shared/build.gradle.kts`:
  - `buildAgentBinaries` compila los 13 destinos con `CGO_ENABLED=0` (y
    `GOARM=7` en `arm`), empaqueta los seis principales y escribe el
    manifiesto con los 13.
  - `-PtitanAgentPrebuilt=<dir>` toma los binarios de un directorio de assets
    del Release en vez de compilarlos.
  - `agentReleaseAssets` los copia con el nombre del Release.
- `release.yml`: un job `agent` nuevo compila los binarios una vez y los sube
  como artefacto. `test`, `android`, `linux` y `windows` los usan con
  `-PtitanAgentPrebuilt`, así que el SHA-256 fijado en cada paquete es el de
  los assets publicados. `publish` los adjunta al Release, y `SHA256SUMS` los
  cubre. Ver [[Pipeline de release en GitHub Actions]].

### Hallazgos

- **Win32-OpenSSH envuelve el comando**: ejecuta `cmd.exe /c "<comando>"`,
  como muestra `%CMDCMDLINE%`. Basta con `"<ruta>" args`, y funciona con
  espacios, paréntesis y `&` en la ruta. El doble entrecomillado que parecía
  necesario por las reglas de `cmd /c` rompe el comando.
- **sshj y SFTP**: pasar todo el binario en una sola escritura a
  `RemoteFileOutputStream` genera un `SSH_FXP_WRITE` que no cabe en la
  ventana del canal ("Timeout when trying to expand the window size"). Se
  trocea por el tamaño máximo de paquete, como hace el `SFTPFileTransfer` de
  sshj.
- **ACK tras cerrar**: con la salida más lenta (emulación), una trama `DATA`
  podía llegar mientras `AgentTransport.close()` cerraba el canal, y el `ACK`
  lanzaba "Stream closed". Ahora el `ACK` perdido se ignora: solo hace que el
  agente guarde algo más de historial para el replay.
- La APK de release pasa de 8,5 MB a 14 MB con los seis binarios, como
  preveía ADR-0010.

### Verificación

- Tests unitarios: `AgentInstallTest` (mapeos, rutas, comillas, hashes,
  limpieza), `AgentInstallerTest` (host falso con SFTP en memoria: Windows con
  cmd y con PowerShell, Unix con y sin SFTP, idempotencia, instalación
  corrupta, `.exe` en uso, destinos no soportados), `AgentDownloaderTest`
  (checksum fijado, caché, fallos) y `AgentBinariesResourceTest` (seis
  empaquetados con cabecera ELF, Mach-O o PE, y los 13 fijados). 146 tests en
  verde.
- **Windows**, por SSH contra el sshd local con el usuario estándar temporal:
  `AgentInstallerIntegrationTest` (detección `windows-amd64` con cmd, subida,
  sin resubir gracias a `certutil`, `--version`, y borrado de la versión
  anterior) y `AgentTransportIntegrationTest` (el marcador vuelve en el replay
  al reconectar). Además, a mano, el lanzamiento y `certutil` con una ruta con
  espacios, paréntesis y `&`.
- **Linux**, con sshd en Docker: amd64 (los dos tests de integración y el de
  scripts de inicio), amd64 sin subsistema SFTP (alternativa `head -c`, el
  binario queda en `0700`), y **arm** (armv7) y **riscv64** emulados con
  QEMU, dos de los destinos que se descargan.
- **Descarga real**: `httpGet` contra un asset que ya existe (`SHA256SUMS` de
  `v0.1.0-beta.2`) sigue la redirección de GitHub, y un 404 se trata como
  fallo.
- `:androidApp:assembleDebug` y `assembleRelease` (R8): las dos APK llevan
  los seis binarios y el manifiesto.
- `-PtitanAgentPrebuilt` da los mismos checksums que compilar, y la falta de
  un binario se detecta.
- No verificado en ejecución: macOS y FreeBSD (solo tests unitarios y
  compilación), PowerShell como `DefaultShell` (solo el test con host falso) y
  Windows arm64.

### Pendiente para pasar a `Hecha`

1. El pipeline nuevo aún no se ha ejecutado. En la siguiente pre-release (o
   con un `workflow_dispatch`), comprobar que el job `agent` termina, que el
   Release lleva los 13 `titan-agent-<versión>-*`, que `sha256sum -c
   SHA256SUMS` los da por buenos y que coinciden con el manifiesto de la APK.
La cuenta de pruebas de Windows **se conserva** por decisión del usuario
(2026-09-27); de su perfil ya se han borrado los binarios y el estado del
agente, así que no queda limpieza pendiente.
