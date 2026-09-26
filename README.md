# titan-ssh

Cliente SSH multiplataforma (Android + escritorio Windows/Linux) construido con
**Kotlin Multiplatform + Compose Multiplatform**. Alternativa a Termius centrada
en sesiones que sobreviven a los microcortes de red y en automatización por
sesión sin muros de pago.

> La fuente de verdad del proyecto (reglas, decisiones, tareas) vive en la bóveda
> [`docs/`](docs/README.md). Cualquier agente o persona debe leerla antes de
> trabajar.

## Instalar

Todas las versiones se publican en
[GitHub Releases](https://github.com/danielperezmartinez/titan-ssh/releases),
generadas por CI a partir de un tag. Las que llevan sufijo (`-alpha.N`,
`-beta.N`, `-rc.N`) son versiones de prueba y aparecen como *pre-release*.
Cada Release trae un `SHA256SUMS` para comprobar las descargas.

### Android

La forma recomendada es [Obtainium](https://github.com/ImranR98/Obtainium), que
instala la APK desde los Releases y avisa de cada versión nueva:

1. Instala Obtainium (desde su página de Releases o desde IzzyOnDroid).
2. Pulsa **Añadir aplicación** y pega la URL del repositorio:
   `https://github.com/danielperezmartinez/titan-ssh`.
3. Mientras solo haya versiones de prueba, activa **Incluir versiones
   preliminares** (*Include prereleases*). Si no, Obtainium no encontrará
   ninguna.
4. Instala. Las actualizaciones siguientes se instalan encima y conservan los
   hosts y sesiones guardados.

También se puede descargar a mano `titan-ssh-<versión>.apk` del Release. El
`.aab` es para Google Play y no se instala directamente.

Todas las APK publicadas están firmadas con la misma clave. Huella SHA-256 del
certificado, para comprobarla (por ejemplo con AppVerifier o
`apksigner verify --print-certs`):

```
39:29:D8:99:7A:C2:B2:3F:61:51:7A:05:61:BB:B1:0F:E3:D9:E4:A0:A6:54:7E:E2:8E:70:3C:87:C3:EC:DE:AB
```

Una APK compilada en local (`assembleDebug`) va firmada con la clave de debug
de quien la compila: Android no deja instalarla encima de la publicada, y
desinstalar la publicada borra sus datos. Las versiones de desarrollo
anteriores al cambio de identificador (`im.gar.titanssh`) son otra app: pueden
convivir con esta y se desinstalan aparte.

### Windows

`titan-ssh-<versión>-windows-x64.msi`. Se instala para el usuario actual, sin
permisos de administrador, y una versión nueva se instala encima de la
anterior. Mientras el instalador no esté firmado, SmartScreen mostrará un aviso
(**Más información → Ejecutar de todas formas**).

### Linux

- Debian, Ubuntu y derivadas: `titan-ssh_<versión>_amd64.deb`
  (`sudo apt install ./titan-ssh_…deb`).
- Fedora y derivadas: `titan-ssh-<versión>-1.x86_64.rpm`
  (`sudo dnf install ./titan-ssh-…rpm`).
- Cualquier distribución: `titan-ssh-<versión>-linux-x64.tar.gz`. Se
  descomprime y se ejecuta `titan-ssh/bin/titan-ssh`.

## Estructura

Tres módulos Gradle (topología nativa de AGP 9 para apps KMP + Compose):

- `shared/` — **librería KMP** compartida (`com.android.kotlin.multiplatform.library`).
  - `src/commonMain` — dominio y UI compartida (Compose Multiplatform) + interfaces `expect`.
  - `src/androidMain` — `actual` de Android.
  - `src/desktopMain` — `actual` de escritorio (JVM, Windows/Linux).
- `androidApp/` — **app Android** (`com.android.application`), consume `shared`.
- `desktopApp/` — **app de escritorio** (JVM + Compose), consume `shared`.

Ver [ADR-0002](docs/Decisiones/ADR-0002%20Stack%20KMP%20y%20alcance%20multiplataforma.md)
(stack y alcance) y
[ADR-0006](docs/Decisiones/ADR-0006%20Estructura%20multi-módulo%20KMP%20para%20AGP%209.md)
(topología de módulos).

## Requisitos

- **JDK 17+** (el proyecto se ha inicializado con el JBR 21 de Android Studio).
- **Android SDK** con `compileSdk 37` (Android 17) y build-tools 37 (ruta en
  `local.properties`). `minSdk 26` para retrocompatibilidad.

Stack: Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.20, Compose Multiplatform 1.9.3.

## Comandos

```bash
# Escritorio: ejecutar la app
./gradlew :desktopApp:run

# Android: construir el APK de debug
./gradlew :androidApp:assembleDebug

# Empaquetado nativo de escritorio (Windows .msi / Linux .deb)
./gradlew :desktopApp:packageDistributionForCurrentOS

# Versión resuelta y valores derivados (versionCode, MSI, deb, rpm)
./gradlew printVersion
```

Para publicar una versión basta con crear el tag en `main` y subirlo; el
workflow [`release.yml`](.github/workflows/release.yml) compila, prueba y
publica el Release:

```bash
git tag v0.1.0-beta.1 && git push origin v0.1.0-beta.1
```

## Idioma

Código, identificadores y comentarios en **inglés**; la documentación de `docs/`
en **español** (convención del proyecto).

## Licencia

Copyright (C) 2026 Daniel Pérez Martínez

titan-ssh es software libre: puedes redistribuirlo y/o modificarlo según los
términos de la **GNU General Public License** publicada por la Free Software
Foundation, en su versión 3 o (a tu elección) cualquier versión posterior
(`SPDX-License-Identifier: GPL-3.0-or-later`).

Se distribuye con la esperanza de que sea útil, pero **SIN NINGUNA GARANTÍA**,
ni siquiera la garantía implícita de COMERCIABILIDAD o IDONEIDAD PARA UN FIN
DETERMINADO. Consulta el texto completo en [`LICENSE`](LICENSE).

Los componentes de terceros (bibliotecas, la fuente JetBrains Mono y las
dependencias del agente Go) conservan sus propias licencias; ver
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
