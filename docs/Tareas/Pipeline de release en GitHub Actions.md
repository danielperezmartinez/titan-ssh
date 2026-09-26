---
Nombre: 'Pipeline de release en GitHub Actions'
Estado: 'En curso'
Resumen: 'Workflow de GitHub Actions (gratis en repositorios públicos) disparado por un tag vX.Y.Z que genera todos los artefactos y los publica en un GitHub Release: MSI (runner Windows, porque jpackage no compila para otra plataforma), .deb, .rpm y tar.gz (runner Ubuntu), APK firmada y AAB, más SHA256SUMS. Instala Go y hace obligatorio el agente, ejecuta los tests, marca como pre-release los tags con sufijo (canal interno del usuario) y, si ADR-0010 elige GitHub Releases, publica también los binarios del agente descargables. Es la base de todos los canales.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3. Fuente única de artefactos para todos los canales.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-26T18:20:00+02:00
---

# Pipeline de release en GitHub Actions

## Objetivo

Que `git tag v0.1.0 && git push --tags` produzca, sin pasos manuales, un GitHub
Release con todos los instaladores firmados donde proceda.

## Contexto

- Repositorio: `github.com/danielperezmartinez/titan-ssh` (público). Antes de
  esta tarea no había `.github/`.
- jpackage **no compila para otra plataforma**: el MSI requiere un runner
  Windows (Compose descarga WiX automáticamente) y el `.deb`/`.rpm` un runner
  Linux (`rpm` instalado para `.rpm`).
- Gradle se ejecuta con JDK 21; el proyecto compila a JVM 17.
- El agente Go se cross-compila en `buildAgentBinaries` (hace falta
  `actions/setup-go`; la versión se fija en el workflow, ver **Resultado**). Con una versión de
  release (`-PtitanVersion`), la build **falla** si no hay Go o si no compila
  algún destino.
- Hallazgos de [[Configuración de release del escritorio]] (2026-09-24):
  - Tareas: `:desktopApp:packageMsi` (Windows), `packageDeb`, `packageRpm` y
    `packageTarGz` (Linux; la última solo se ejecuta en Linux). Se usan las
    `package*`, no las `packageRelease*` (sin ProGuard). Las de escritorio no
    necesitan el SDK de Android.
  - jpackage toma las dependencias del paquete de la máquina donde compila.
    El **`.deb` hay que compilarlo en Ubuntu 22.04** (en 24.04 depende de
    `libpng16-16t64`, que no existe en 22.04 ni en Debian 12). El **`.rpm`,
    dentro de un contenedor Fedora** (en Ubuntu solo declara `xdg-utils` y en
    un Fedora mínimo falta `libfontconfig`). **Corregido el 2026-09-26**: en
    Fedora tampoco declara más que `xdg-utils`; ver **Verificación**.
  - Un JDK 21 completo (Temurin) sirve para todo: un `eclipse-temurin:21-jdk`
    con Go, `rpm` y `fakeroot` generó los tres paquetes de Linux.

## Criterios de finalización

- `.github/workflows/release.yml` con `on: push: tags: ['v*']`:
  - **windows-latest**: `packageReleaseMsi` (o `packageMsi`).
  - **ubuntu (versión antigua por glibc)**: `.deb`, `.rpm`, `tar.gz` de
    `createDistributable`, APK y AAB de release, y tests (`desktopTest`,
    `go test ./...` del agente).
  - Hecho el 2026-09-26: ver **Resultado**. Primer Release `v0.1.0-beta.1`
    publicado; ver **Verificación**.
  - **Publicación**: `gh release create` con todos los artefactos,
    `SHA256SUMS` y notas generadas. Pre-release si el tag contiene `-`.
- Versión inyectada desde el tag ([[Versionado único desde tag de git]]):
  `./gradlew ... -PtitanVersion=${GITHUB_REF_NAME#v}`. El build rechaza tags
  que no sean `X.Y.Z` o `X.Y.Z-(alpha|beta|rc).N`. Para el MSI hace falta un
  JDK completo con jpackage (el JBR de Android Studio no lo trae).
- Secretos: keystore de Android (base64 + contraseñas). Más adelante se añaden
  los de SignPath, AUR y winget en sus tareas.
- Seguridad del workflow: `permissions` mínimos (`contents: write` solo en el
  job de publicación), acciones de terceros **fijadas por SHA**, y los secretos
  nunca disponibles en workflows lanzados desde PRs de forks.
- Caché de Gradle (`gradle/actions/setup-gradle`) y de módulos Go.
- **Agente**: la build falla si no se generan los binarios. Si
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] elige GitHub
  Releases como origen de descarga, este workflow publica también los binarios
  no empaquetados con sus nombres estables. Sus SHA-256 van fijados en la app en
  la misma build.
- Opcional, en otra tarea si crece: `ci.yml` que compile y pase los tests en cada
  PR/push a `main`; runners ARM64 gratuitos para instaladores ARM de Windows y
  Linux.
- Primer release real publicado (pre-release `v0.1.0-beta.1` o similar) y
  descargado en Windows, Linux y el Pixel.

## Verificación

**Antes del primer tag** (2026-09-26), reproduciendo los jobs de Linux en
Docker con las mismas herramientas que el workflow (Temurin 21, Go 1.27.1 y
`-PtitanVersion=0.1.0-beta.1`):

- `actionlint` (con shellcheck) no da ningún aviso.
- **Ubuntu 22.04**: `go test -race ./...` del agente pasa. `:shared:desktopTest`
  da 114 tests sin fallos. Los de integración y `DesktopSecretStoreTest` salen
  sin comprobar nada cuando no hay host de pruebas ni llavero del sistema (así
  están escritos), de modo que en CI cuentan como aprobados. Se generan el
  `.deb` y el `tar.gz`.
- **Dependencias del `.deb`**: en un Ubuntu mínimo solo declara `libc6,
  xdg-utils`, porque jpackage solo declara las librerías que encuentra
  instaladas. Con las librerías de la app instaladas (como hace el job)
  declara `libasound2, libfontconfig1, libfreetype6, libpng16-16, libx11-6,
  libxext6, libxi6, libxrender1, libxtst6, …, xdg-utils`, con nombres válidos
  en 22.04 y en Debian 12.
- **Dependencias del `.rpm`**: siempre `xdg-utils` y nada más, **aunque se
  compile dentro de Fedora 44** con las librerías instaladas. La salida
  detallada de jpackage muestra que sí resuelve los paquetes (`Required
  packages: [alsa-lib, fontconfig, freetype, libX11, …]`), pero no los escribe
  en el `Requires` del spec. No es cosa de rpm 6: un spec de prueba con esa
  misma línea `Requires` sale bien. Por eso **el `.rpm` se compila en el
  mismo job de Ubuntu** y no en un contenedor Fedora, que no aportaba nada. En
  un Fedora de escritorio esas librerías ya están. Si hiciera falta, la
  solución sería un spec propio (`--resource-dir` de jpackage, que Compose no
  expone) o reempaquetar el rpm. Flatpak ([[Canal Linux Flatpak en Flathub]])
  sigue siendo el canal principal de Linux.

**Primer Release real** (2026-09-26): el tag `v0.1.0-beta.1` lanzó el run
`36254535732` y los seis jobs acabaron bien. El Release
[`titan-ssh 0.1.0-beta.1`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.1)
salió marcado como pre-release, con la APK (8,5 MB), el AAB, el MSI, el
`.deb`, el `.rpm`, el `tar.gz` y `SHA256SUMS`.

- Descargados en Windows, los checksums cuadran. `apksigner` confirma que la
  APK está firmada con la clave de release (`3929d899…`) y que lleva los tres
  binarios del agente.
- El `.deb` publicado declara las librerías reales (`libasound2`,
  `libfontconfig1`, `libfreetype6`, `libgl1`, `libx11-6`, …, `xdg-utils`).
  En un Ubuntu 22.04 limpio, `apt install` resuelve las dependencias y la app
  sigue en marcha a los 20 s bajo Xvfb.
- El `.rpm` publicado se instala con `dnf` en un Fedora 44 limpio y la app
  arranca igual, una vez están las librerías de X que no declara.
- **Fallo encontrado**: GitHub cambia `~` por `.` en el nombre de los assets.
  El `.deb` y el `.rpm` se publicaron como `titan-ssh_0.1.0.beta.1_amd64.deb`
  y `titan-ssh-0.1.0.beta.1-1.x86_64.rpm`, pero `SHA256SUMS` los lista con
  `~`, así que `sha256sum -c` no los encuentra (los hashes sí coinciden). Se
  corrigió en el workflow: el job `linux` cambia `~` por `-` en los nombres de
  fichero antes de subirlos, y la versión de dentro del paquete conserva el
  `~`. El Release `v0.1.0-beta.1` se queda como está; el arreglo sale a
  partir de `v0.1.0-beta.2`.

Falta: la prueba de Obtainium en el Pixel
([[Canal Android Obtainium desde GitHub Releases]]) y comprobar en
`v0.1.0-beta.2` que el arreglo de los nombres funciona.

## Resultado

`.github/workflows/release.yml`, más el bit de ejecución de `gradlew` en git
(estaba en `100644` y un runner Linux no podía ejecutarlo).

- **Disparadores**: un tag `v*` publica el Release. `workflow_dispatch` con una
  versión de entrada compila todo y deja los paquetes como artefactos del run,
  **sin publicar**, para probar el pipeline sin gastar una versión.
- **Jobs**:
  - `version`: quita la `v` del tag, valida la forma y exige que el commit del
    tag esté en `main`. Una versión con `-` es pre-release.
  - `test` (Ubuntu 22.04): `go test -race` del agente y `:shared:desktopTest`.
  - `android`: APK y AAB de release firmados. El keystore se decodifica en
    `$RUNNER_TEMP` y se borra al acabar. **El job falla si la APK no está
    firmada o si la huella del certificado no es la de la clave de release**
    (`3929d899…`, fijada en el workflow), para no publicar nunca una APK que
    los usuarios de Obtainium no puedan instalar encima.
  - `linux` (Ubuntu 22.04): `.deb`, `.rpm` y `tar.gz`, con las librerías de la
    app instaladas antes para que el `.deb` las declare.
  - `windows`: MSI.
  - `publish` (solo con tag, el único con `contents: write`): junta los
    artefactos, genera `SHA256SUMS` y crea el Release con `gh release create
    --verify-tag --generate-notes`, con `--prerelease` si toca.
- **Nombres publicados**: `titan-ssh-<versión>.apk`, `titan-ssh-<versión>.aab`,
  `titan-ssh-<versión>-windows-x64.msi` (jpackage lo llama por su versión
  interna `X.Y.(Z*100+S)`), `titan-ssh_<versión>_amd64.deb`,
  `titan-ssh-<versión>-1.x86_64.rpm` y `titan-ssh-<versión>-linux-x64.tar.gz`
  (desde `v0.1.0-beta.2`; en el nombre del `.deb` y del `.rpm` el `~` de su
  versión interna se cambia por `-`).
- **Seguridad**: `permissions: contents: read` por defecto. Acciones fijadas
  por SHA (checkout v7.0.1, setup-java v6.0.1, setup-go v7.0.0,
  gradle/actions v6.3.0, upload-artifact v7.0.1, download-artifact v8.0.1).
  Para publicar se usa el `gh` del runner, sin acciones de terceros. El
  workflow no se ejecuta en PRs, así que los secretos no llegan a forks.
- **Go**: se fija `1.27.x` en el workflow en vez de leerlo de `agent/go.mod`.
  El `go 1.22` de `go.mod` es la versión mínima del lenguaje, y compilar los
  binarios que se distribuyen con un Go sin soporte arrastraría fallos ya
  corregidos de la librería estándar.
- **Secretos** (GitHub → Settings → Secrets and variables → Actions):
  `TITAN_KEYSTORE_BASE64`, `TITAN_KEY_ALIAS` y `TITAN_KEYSTORE_PASSWORD`. La
  contraseña de la clave es la del almacén (PKCS12).
- **Caché**: `setup-gradle` y `setup-go` guardan caché, pero GitHub solo
  comparte entre refs la caché de la rama por defecto. Mientras no haya un
  `ci.yml` en `main`, cada tag compila en frío (unos minutos más; no bloquea).
- **Binarios del agente sueltos**: no se publican todavía, porque
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] sigue sin elegir
  el origen de descarga. Se decide en
  [[Instalación del agente en destinos Windows y multi-SO]] (paso 9.5 de
  [[Seguimiento de tareas pendientes]]).
