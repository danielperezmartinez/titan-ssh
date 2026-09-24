---
Nombre: 'Configuración de release del escritorio'
Estado: 'Pendiente'
Resumen: 'Dejar compose.desktop listo para distribuir. Añadir un upgradeUuid fijo (sin él, el MSI no se actualiza sobre sí mismo), metadatos (vendor, descripción, copyright, licencia, categoría), iconos, formato .rpm además de MSI y .deb, y un tar.gz de la imagen de la app para AUR y Flatpak. También fijar los módulos del JRE embebido (jpackage recorta el runtime y sshj/BouncyCastle suelen fallar solo empaquetado), hacer obligatorio el agente Go en builds de release y decidir si se usa ProGuard. Verificar instalando de verdad en Windows y Linux.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3–4.'
Bloqueada:
  - "[[Icono y recursos gráficos de la app]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-24T14:00:00+02:00
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

<Se rellena al completar: instalar, actualizar y desinstalar en Windows 10 y en Linux (Arch/Bazzite para tar.gz, un Debian/Ubuntu para .deb y un Fedora para .rpm).>

## Resultado

<Se rellena al completar.>
