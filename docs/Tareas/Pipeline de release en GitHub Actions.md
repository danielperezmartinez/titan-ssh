---
Nombre: 'Pipeline de release en GitHub Actions'
Estado: 'Pendiente'
Resumen: 'Workflow de GitHub Actions (gratis en repositorios públicos) disparado por un tag vX.Y.Z que genera todos los artefactos y los publica en un GitHub Release: MSI (runner Windows, porque jpackage no compila para otra plataforma), .deb, .rpm y tar.gz (runner Ubuntu), APK firmada y AAB, más SHA256SUMS. Instala Go y hace obligatorio el agente, ejecuta los tests, marca como pre-release los tags con sufijo (canal interno del usuario) y, si ADR-0010 elige GitHub Releases, publica también los binarios del agente descargables. Es la base de todos los canales.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3. Fuente única de artefactos para todos los canales.'
Bloqueada:
  - "[[Configuración de release del escritorio]]"
  - "[[Firma y configuración de release Android]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Pipeline de release en GitHub Actions

## Objetivo

Que `git tag v0.1.0 && git push --tags` produzca, sin pasos manuales, un GitHub
Release con todos los instaladores firmados donde proceda.

## Contexto

- Repositorio: `github.com/danielperezmartinez/titan-ssh` (público). Hoy no hay
  `.github/`.
- jpackage **no compila para otra plataforma**: el MSI requiere un runner
  Windows (Compose descarga WiX automáticamente) y el `.deb`/`.rpm` un runner
  Linux (`rpm` instalado para `.rpm`).
- Gradle se ejecuta con JDK 21; el proyecto compila a JVM 17.
- El agente Go se cross-compila en `buildAgentBinaries` (hace falta
  `actions/setup-go`; la versión, de `agent/go.mod`).

## Criterios de finalización

- `.github/workflows/release.yml` con `on: push: tags: ['v*']`:
  - **windows-latest**: `packageReleaseMsi` (o `packageMsi`).
  - **ubuntu (versión antigua por glibc)**: `.deb`, `.rpm`, `tar.gz` de
    `createDistributable`, APK y AAB de release, y tests (`desktopTest`,
    `go test ./...` del agente).
  - **Publicación**: `gh release create` con todos los artefactos,
    `SHA256SUMS` y notas generadas. Pre-release si el tag contiene `-`.
- Versión inyectada desde el tag ([[Versionado único desde tag de git]]).
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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
