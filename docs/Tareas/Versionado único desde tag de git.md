---
Nombre: 'Versionado único desde tag de git'
Estado: 'Pendiente'
Resumen: 'Una sola versión para Android, escritorio y agente, sacada del tag de git vX.Y.Z[-sufijo]. Hoy no coinciden: escritorio en packageVersion 1.0.0, Android en versionName 0.1.0 / versionCode 1. Derivar versionName, un versionCode monótono, el packageVersion del MSI/deb (con sus límites de formato) y la versión del agente de una única fuente en Gradle, con valor de desarrollo cuando no hay tag.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §3.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
