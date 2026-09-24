---
Nombre: 'Versionado único desde tag de git'
Estado: 'Hecha'
Resumen: 'Una sola versión para Android, escritorio y agente, sacada del tag de git vX.Y.Z[-sufijo]. Hoy no coinciden: escritorio en packageVersion 1.0.0, Android en versionName 0.1.0 / versionCode 1. Derivar versionName, un versionCode monótono, el packageVersion del MSI/deb (con sus límites de formato) y la versión del agente de una única fuente en Gradle, con valor de desarrollo cuando no hay tag.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3. El agente comparte la versión de la app (no lleva una propia): se estampa con -ldflags; la compatibilidad entre versiones la da el protocolo en el HELLO. versionCode = X*1000000 + Y*10000 + Z*100 + rango de pre-release (alpha N, beta 30+N, rc 60+N, estable 99). MSI con el rango en el tercer campo (X.Y.Z*100+rango) para que una beta actualice a otra; deb con ~ para el pre-release.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-24T14:00:00+02:00
---

# Versionado único desde tag de git

## Objetivo

Que al crear el tag `vX.Y.Z` todos los artefactos lleven la misma versión sin
editar a mano varios ficheros.

## Contexto

- `desktopApp/build.gradle.kts`: `packageVersion = "1.0.0"`.
- `androidApp/build.gradle.kts`: `versionCode = 1`, `versionName = "0.1.0"`.
- El agente tiene su propia versión (la instalación usa una ruta versionada
  `agent-<ver>-<os>-<arch>`, ver
  [[titan-agent distribución multi-arch e instalación]]); decidir si se alinea
  con la de la app o sigue siendo independiente (el protocolo lleva su propia
  versión en el HELLO).

## Criterios de finalización

- SemVer `X.Y.Z` y sufijo opcional de pre-release (`-beta.N`), primera versión
  pública propuesta: `0.1.0`.
- Una única fuente en Gradle: propiedad `-Pversion=...` que pasa CI a partir del
  tag, o lectura de `git describe`. Fuera de un tag, valor de desarrollo
  (p. ej. `0.0.0-dev`) que no pueda confundirse con una versión real.
- `versionCode` **estrictamente creciente**, incluidos los pre-releases,
  p. ej. `X*1_000_000 + Y*1_000 + Z` con el pre-release por debajo de la
  estable (o el número de ejecución de CI). Documentar la fórmula: Obtainium,
  IzzyOnDroid y Play rechazan versiones que no aumentan.
- `packageVersion` de escritorio sin sufijo y dentro de los límites de
  jpackage/MSI (Windows: `MAJOR ≤ 255`, `MINOR ≤ 255`, `PATCH ≤ 65535`;
  **comprobar si jpackage acepta `MAJOR = 0` en el MSI**; si no, decidir el
  mapeo).
- La versión se muestra en la app (Acerca de, ver
  [[Licencia GPL-3.0-or-later del proyecto]]).

## Verificación

2026-09-24, paso 2 de [[Seguimiento de tareas pendientes]]:

- `./gradlew printVersion` con varias entradas: sin nada → `0.0.0-dev`, code
  `1`; `0.1.0-alpha.3` → `10003`; `0.1.0-beta.1` → `10031`, MSI `0.1.31`, deb
  `0.1.0~beta.1`; `0.1.0-beta.2` → `10032`; `0.1.0-rc.1` → `10061`; `0.1.0` →
  `10099`, MSI `0.1.99`; `0.1.1-beta.1` → `10131`; `v1.2.3` → `1020399`.
  Rechazados con error claro: `1.0`, `0.1.0-beta.30`, `0.100.0`.
- Con un tag local temporal `v0.0.9-beta.1` en HEAD, la versión sale del tag;
  `-PtitanVersion` tiene prioridad sobre él. Tag borrado después.
- **jpackage acepta `MAJOR = 0` en el MSI**: `packageMsi` con `0.1.0-beta.1`
  generó `titan-ssh-0.1.31.msi` con `ProductVersion = 0.1.31` (leído de la
  tabla `Property` del MSI). Necesitó un JDK con jpackage (ver
  [[Configuración de release del escritorio]]).
- APK de debug con `0.1.0-beta.1`: `aapt2 dump badging` da
  `versionCode='10031' versionName='0.1.0-beta.1'`, y lleva `legal/LICENSE`,
  `legal/THIRD_PARTY_NOTICES.md` y los binarios del agente.
- Agente: `go build -ldflags "-X main.version=0.1.0-beta.1"` → `titan-agent
  -version` imprime `titan-agent 0.1.0-beta.1`; los binarios del build de
  desarrollo llevan `0.0.0-dev`. `go vet` limpio.
- `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin` y
  `:shared:desktopTest` en verde.
- Pantalla Acerca de con la versión, en escritorio (Windows 10) y en el
  emulador `Pixel_9_Pro_XL` (Android 15), ver
  [[Licencia GPL-3.0-or-later del proyecto]].

## Resultado

- **Fuente única**: el build raíz (`build.gradle.kts`) resuelve `titanVersion`
  por este orden: `-PtitanVersion=X.Y.Z[-pre]` (CI lo pasa desde el tag; se
  admite la `v` inicial), el tag `vX.Y.Z[-pre]` que apunta exactamente a HEAD,
  y si no, `0.0.0-dev`. Solo admite `X.Y.Z` y pre-releases `alpha.N`,
  `beta.N` y `rc.N` con N de 1 a 29; lo demás rompe el build. Publica en
  `rootProject.extra` los valores derivados; `./gradlew printVersion` los
  muestra.
- **Android**: `versionName` = versión completa; `versionCode` =
  `X*1_000_000 + Y*10_000 + Z*100 + S`, con `S` = N (alpha), 30+N (beta),
  60+N (rc) y 99 (estable). Así `0.1.0-beta.1 < 0.1.0-rc.1 < 0.1.0 <
  0.1.1-beta.1`. Límites: Y y Z ≤ 99. El build de desarrollo usa 1.
- **Escritorio**: `packageVersion` = `X.Y.Z`; `msiPackageVersion` =
  `X.Y.(Z*100+S)`, porque Windows Installer solo compara los tres primeros
  campos y sin el rango una beta no actualizaría a la anterior (límites X ≤ 255
  y Z ≤ 654; en la práctica manda el de Android, Z ≤ 99); `debPackageVersion` =
  `X.Y.Z~pre`, que Debian ordena por debajo de la final.
- **Agente**: comparte la versión de la app. `main.version` pasa a `var` y
  Gradle la estampa con `-ldflags -X` (con la versión como entrada de la tarea,
  para que se recompile al cambiarla). La ruta instalada
  `agent-<versión>-<os>-<arch>` sale de `BuildInfo.VERSION`.
- **En el código**: objeto generado `BuildInfo` (`VERSION`, `IS_RELEASE`) en
  `commonMain`, registrado en el catálogo como [[BuildInfo]].

Pendiente para otras tareas:

- Cada versión instala un binario nuevo del agente en
  `~/.local/share/titan-ssh` y los antiguos se quedan. No se borran todavía:
  un daemon en marcha puede seguir usando uno. Encaja en
  [[titan-agent instancia única y directorio de estado]] o en
  [[Instalación del agente en destinos Windows y multi-SO]].
- CI debe pasar `-PtitanVersion=${tag#v}` en
  [[Pipeline de release en GitHub Actions]].
