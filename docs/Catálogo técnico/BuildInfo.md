---
Nombre: "BuildInfo"
Tipo: "Utilidad"
Área: "Plataforma"
Feature: "Distribución"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/build.gradle.kts"
Entrada pública: "io.github.danielperezmartinez.titanssh.BuildInfo"
Resumen: "Objeto generado en commonMain por la tarea generateBuildInfo con la versión única de la app: VERSION (la misma que versionName de Android, la de los paquetes de escritorio y la estampada en el agente) e IS_RELEASE (false en un build de desarrollo, 0.0.0-dev). Es la única forma de conocer la versión desde el código; no se escribe la versión a mano en ningún otro sitio. Junto a él, readLegalText(LegalText) lee LICENSE y THIRD_PARTY_NOTICES.md empaquetados como recursos /legal/."
Última modificación: 2026-09-24T14:00:00+02:00
---

# BuildInfo

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
la tarea `generateBuildInfo` de
[shared/build.gradle.kts](../../shared/build.gradle.kts) y la resolución de la
versión en el [build raíz](../../build.gradle.kts) (orden: `-PtitanVersion`, tag
`vX.Y.Z` exacto en HEAD, `0.0.0-dev`; fórmulas de `versionCode`, MSI y deb).
`./gradlew printVersion` muestra todos los valores derivados.

Los textos legales se leen con `readLegalText` (`expect` en
[LegalTexts.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/LegalTexts.kt),
`actual` en `jvmShared`), que carga las copias de la tarea `bundleLegalTexts`.

Consumidores: la pantalla Acerca de (`ui/AboutScreen.kt`), `AgentBinaries` y
`AgentInstaller` (ruta versionada del agente). Lo usará también
[[Aviso de nueva versión en la app]]. Decisión en
[[Versionado único desde tag de git]].
