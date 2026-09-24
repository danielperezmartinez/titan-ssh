---
Nombre: 'Configuración de release del escritorio'
Estado: 'En curso'
Resumen: 'Dejar compose.desktop listo para distribuir. Añadir un upgradeUuid fijo (sin él, el MSI no se actualiza sobre sí mismo), metadatos (vendor, descripción, copyright, licencia, categoría), iconos, formato .rpm además de MSI y .deb, y un tar.gz de la imagen de la app para AUR y Flatpak. También fijar los módulos del JRE embebido (jpackage recorta el runtime y sshj/BouncyCastle suelen fallar solo empaquetado), hacer obligatorio el agente Go en builds de release y decidir si se usa ProGuard. Verificar instalando de verdad en Windows y Linux. Estado a 2026-09-24: hecho y verificado todo salvo los iconos, que esperan al paso 3.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3–4. Instalación por usuario en Windows (sin UAC) y sin ProGuard; upgradeUuid fijado en aa0fe179-f4a1-4db1-92f3-f3b04240e849.'
Bloqueada:
  - "[[Icono y recursos gráficos de la app]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-24T14:10:00+02:00
---

# Configuración de release del escritorio

## Objetivo

Que `packageMsi`, `packageDeb`, `packageRpm` y `createDistributable` generen
instaladores correctos y actualizables, y que la app empaquetada funcione igual
que en desarrollo.

## Contexto

Estado actual de `desktopApp/build.gradle.kts`: `targetFormats(Msi, Deb)`,
`packageName = "titan-ssh"` y las versiones derivadas de la versión única
(`packageVersion`, `msiPackageVersion`, `debPackageVersion`; ver
[[Versionado único desde tag de git]]), y nada más (ni `upgradeUuid`, ni
iconos, ni módulos, ni metadatos). El Gradle se ejecuta con el JDK 21 de
Android Studio ([[build-toolchain]]); jpackage empaqueta un JRE recortado de
ese JDK.

Hallazgos del 2026-09-24 (paso 2 de la hoja de ruta):

- **El JBR de Android Studio no trae `jpackage.exe`**: `packageMsi` falla en
  `checkRuntime`. En local funcionó con
  `-Dorg.gradle.java.home=<ruta-a-un-jdk-completo>` (un JDK 21 con
  jpackage). CI necesitará un JDK completo (p. ej. Temurin 21).
- El MSI de prueba (`0.1.0-beta.1` → `titan-ssh-0.1.31.msi`) salió con
  `UpgradeCode {F46AC738-AE68-3F0F-82BD-F05A52008888}`, que jpackage deriva
  del nombre. Conviene fijar `upgradeUuid` explícito para no depender de eso.
- El `.deb` no se ha generado todavía (solo se puede en Linux): comprobar aquí
  que Compose acepta `debPackageVersion` con `~` (`0.1.0~beta.1`).

## Criterios de finalización

- **Windows (MSI)**:
  - `upgradeUuid` generado **una vez** y fijado para siempre (documentarlo en
    esta tarea; si cambia, las actualizaciones se instalan en paralelo).
  - `menuGroup`, `shortcut`, `dirChooser`, `iconFile` (.ico) y
    `licenseFile` (ver [[Licencia GPL-3.0-or-later del proyecto]]). Incluir
    también `LICENSE` y `THIRD_PARTY_NOTICES.md` de la raíz en todos los
    paquetes (MSI, `.deb`, `.rpm`, `tar.gz`).
  - Decidir `perUserInstall` (instalación sin administrador en
    `%LOCALAPPDATA%`, sin UAC; encaja con una herramienta de usuario).
  - Probar la **actualización** de una versión N a N+1 y la instalación
    silenciosa (`msiexec /i ... /quiet`), que necesita winget.
- **Linux**:
  - Añadir `TargetFormat.Rpm`; `debMaintainer`, `appCategory`/`menuGroup`
    (`Network`), `rpmLicenseType = "GPL-3.0-or-later"` e `iconFile` (.png).
  - `tar.gz` de la imagen de `createDistributable` (Compose no lo genera: se
    empaqueta en CI) para [[Canal Arch Linux AUR]] y
    [[Canal Linux Flatpak en Flathub]], con un `.desktop` e iconos hicolor.
  - Compatibilidad de glibc: compilar en la Ubuntu más antigua razonable, porque
    las librerías nativas (Skiko) enlazan contra la glibc del sistema.
- **Metadatos comunes**: `vendor = "Daniel Pérez Martínez"`, `description`,
  `copyright`.
- **JRE embebido**: ejecutar `suggestRuntimeModules` y fijar `modules(...)`.
  Candidatos habituales: `jdk.crypto.ec`, `java.naming`, `jdk.unsupported`,
  `java.security.jgss`, más lo que pida el SecretStore de escritorio
  (DPAPI/Secret Service, ver [[Almacenamiento seguro de credenciales]]).
  Criterio: con la **app instalada** se conecta de verdad a
  [[ssh-test-host]] con clave, se guardan credenciales y se usa el nivel 3.
- **Agente obligatorio en release**: si no hay Go, `buildAgentBinaries` hace
  fallar la build de release (hoy la omite y el nivel AGENT degrada). En
  desarrollo puede seguir siendo opcional. Empaquetar los destinos fijados por
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]].
- **ProGuard** (`packageRelease*`): decidir si se usa para reducir tamaño; si se
  usa, reglas de keep para sshj, BouncyCastle, kotlinx.serialization y
  reflexión de Compose, y probar la app empaquetada.
- Arquitecturas: x64 obligatoria; ARM64 (Windows y Linux) opcional si CI
  ofrece runners ARM gratuitos (ver [[Pipeline de release en GitHub Actions]]).

## Verificación

Hecha el 2026-09-24 en la rama `fase2-release` (worktree aparte, porque el
paso 3 avanzaba en paralelo en `main`), salvo los iconos.

- **Windows 10 (MSI)**: `packageMsi -PtitanVersion=0.1.0-beta.1` →
  `titan-ssh-0.1.31.msi`. `msiexec /i … /quiet` sin administrador instala en
  `%LOCALAPPDATA%\titan-ssh`, con acceso directo en el escritorio y carpeta en
  el menú Inicio, y la app arranca. Al instalar encima `0.1.0-beta.2`
  (`0.1.32`), `FindRelatedProducts` detecta la anterior y solo queda la
  0.1.32. `msiexec /x … /quiet` borra la carpeta y los accesos directos. `LICENSE`
  y `THIRD_PARTY_NOTICES.md` quedan en `app\resources`.
- **JRE recortado**: el runtime lleva `java.base java.datatransfer java.xml
  java.prefs java.desktop java.instrument java.logging java.security.sasl
  java.naming java.security.jgss java.transaction.xa java.sql jdk.crypto.ec
  jdk.security.auth jdk.unsupported`. Con una imagen `jlink` de esos mismos
  módulos pasan los 114 tests de `:shared:desktopTest`, incluidos los de
  integración: SSH real, exec, known_hosts, pestaña con scripts de inicio,
  instalación del agente y replay tras reconectar. Como [[ssh-test-host]]
  rechazaba conexiones ese día, el servidor fue un sshd temporal en Docker
  con una clave desechable. El `SecretStore` (JNA sobre el almacén de
  credenciales de Windows) también pasa. **`jdk.crypto.ec` es imprescindible**:
  sin él no hay `KeyPairGenerator` EC (ECDH/ECDSA), y `suggestRuntimeModules`
  no lo detecta.
- **Linux** (paquetes generados en un contenedor `eclipse-temurin:21-jdk` con
  Go, `rpm` y `fakeroot`):
  - `.deb` `titan-ssh_0.1.0~beta.1_amd64.deb`: Compose acepta el `~`. Se
    instala en Ubuntu 24.04, deja la entrada de menú y la app arranca con
    Xvfb. `apt-get remove` lo quita de `/opt`. `dpkg --compare-versions`
    ordena `0.1.0~beta.1 < 0.1.0~beta.2 < 0.1.0`.
  - `.rpm` `titan-ssh-0.1.0~beta.1-1.x86_64.rpm` (licencia
    `GPL-3.0-or-later`): se instala en Fedora, la entrada de menú va a
    `/usr/local/share/applications` y la app arranca en cuanto están las
    librerías de X. `rpm -e` lo quita. `rpm.vercmp` ordena el `~` antes que la
    versión final.
  - `tar.gz`: en Arch se descomprime y arranca. El lanzador y `jspawnhelper`
    conservan el bit de ejecución.

Hallazgos que pasan al pipeline ([[Pipeline de release en GitHub Actions]]):

- jpackage calcula las dependencias del paquete en la máquina donde compila.
  Un `.deb` hecho en Ubuntu 24.04 depende de `libpng16-16t64`, un nombre que
  no existe en Ubuntu 22.04 ni en Debian 12, así que hay que compilarlo en
  **Ubuntu 22.04**.
- El `.rpm` compilado en Ubuntu solo declara `xdg-utils`, porque allí la base
  de datos rpm está vacía. En un Fedora mínimo falta `libfontconfig` y la app
  no arranca. Hay que compilarlo **dentro de un contenedor Fedora**.
- En un contenedor sin `/usr/share/applications` el postinst del `.deb` falla
  (`xdg-desktop-menu: No writable system menu directory found`). En un
  escritorio real ese directorio existe. Es un comportamiento de jpackage y no
  se cambia.

No probado: que una build de release falle sin Go. La lógica es directa, y el
pipeline lo comprobará en la práctica al exigir el agente.

## Resultado

Cambios en `desktopApp/build.gradle.kts`, en `build.gradle.kts` (raíz) y en
`shared/build.gradle.kts`:

- `upgradeUuid = "aa0fe179-f4a1-4db1-92f3-f3b04240e849"`, **fijo para
  siempre**.
- **Decisión**: `perUserInstall = true`. Instala en `%LOCALAPPDATA%` sin
  administrador ni UAC, que es lo que corresponde a una herramienta de
  usuario; winget admite el ámbito de usuario. También `dirChooser`,
  `shortcut`, `menu` y `menuGroup`.
- Metadatos (`vendor`, `description`, `copyright`, `licenseFile`) y en Linux
  `debMaintainer`, que es solo la dirección `noreply` de GitHub porque
  jpackage antepone el `vendor`. Además `appCategory`/`menuGroup = Network`,
  `rpmLicenseType` y `shortcut`.
- `TargetFormat.Rpm` y la versión `titanRpmVersion`, que tiene la misma forma
  que la del deb. `printVersion` la muestra.
- Tarea `:desktopApp:packageTarGz`, que solo se ejecuta en Linux: empaqueta la
  imagen de `createDistributable` más `desktopApp/packaging/linux/titan-ssh.desktop`
  y sale en `build/compose/binaries/main/tar.gz/titan-ssh-<versión>-linux-x64.tar.gz`.
- `LICENSE` y `THIRD_PARTY_NOTICES.md` van en todos los paquetes mediante
  `appResourcesRootDir`.
- `modules(...)` del JRE, con el porqué de cada módulo en el comentario.
- **Agente obligatorio en release**: la raíz publica `titanIsRelease` (vale
  `true` con cualquier versión distinta de la de desarrollo). Con él,
  `buildAgentBinaries` hace fallar la build si no hay Go o si falla algún
  destino. En desarrollo sigue siendo un aviso.
- **Decisión: sin ProGuard**. Se usan las tareas `package*`, no
  `packageRelease*`. sshj, JNA (java-keyring) y kotlinx.serialization usan
  reflexión, y el ahorro de tamaño no compensa el riesgo. El MSI ocupa unos
  83 MB y el `.deb` unos 74 MB.

**Pendiente para cerrar la tarea** (paso 3, [[Icono y recursos gráficos de la app]]):

- `windows.iconFile` (.ico) y `linux.iconFile` (.png). Hasta entonces, el
  `.desktop` que genera jpackage apunta al icono por defecto
  `/opt/titan-ssh/lib/titan-ssh.png`.
- El icono de la ventana en `main.kt`.
- Los iconos hicolor dentro del `tar.gz`. El `titan-ssh.desktop` ya declara
  `Icon=titan-ssh`.
- Arquitectura ARM64: opcional, se decide en el pipeline.
