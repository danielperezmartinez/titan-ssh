---
Nombre: Migrar a estructura multi-módulo (AGP 9)
Estado: Hecha
Resumen: 'Separar el módulo único en una librería KMP compartida (com.android.kotlin.multiplatform.library) más apps finas por plataforma (Android y escritorio), para eliminar los flags deprecados android.builtInKotlin/newDsl que AGP 9 obliga a usar cuando com.android.application y kotlin.multiplatform conviven. Deja el proyecto sin deuda de estructura.'
Decisiones: Se registra como [[ADR-0006 Estructura multi-módulo KMP para AGP 9]]. Refina la topología de módulos de [[ADR-0002 Stack KMP y alcance multiplataforma]] sin cambiar el stack.
Bloqueada: []
Fecha de creación: 2026-09-17T18:45:00+02:00
Última modificación: 2026-09-17T19:05:00+02:00
---

# Migrar a estructura multi-módulo (AGP 9)

## Objetivo

Eliminar los flags de compatibilidad `android.builtInKotlin=false` /
`android.newDsl=false` (deprecados) que se introdujeron al subir a AGP 9,
adoptando la topología de módulos nativa de AGP 9 para apps KMP + Compose:

- `:shared` — librería KMP (`com.android.kotlin.multiplatform.library` +
  `kotlin.multiplatform` + Compose) con `commonMain`/`androidMain`/`desktopMain`.
- `:androidApp` — aplicación Android (`com.android.application` +
  `kotlin.android`), consume `:shared`.
- `:desktopApp` — aplicación JVM de escritorio (`kotlin.jvm` + Compose),
  consume `:shared`.

## Criterios de finalización

- Sin `android.builtInKotlin` / `android.newDsl` en `gradle.properties`.
- Ningún módulo aplica a la vez `com.android.application`/`library` y
  `kotlin.multiplatform`.
- `:androidApp:assembleDebug` genera el APK y `:desktopApp:run` arranca la
  ventana, ambos consumiendo `:shared`.
- Convención de idioma (código en inglés) mantenida.

## Verificación

- **Configuración:** `./gradlew projects` lista `:shared`, `:androidApp`,
  `:desktopApp` sin errores ni flags de compatibilidad.
- **Android:** `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`; genera
  `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (~10,4 MB),
  consumiendo `:shared`.
- **Escritorio:** `:desktopApp:run` compila y arranca la ventana Compose sin
  excepciones (verificado y cerrado), consumiendo `:shared`.
- `gradle.properties` sin `android.builtInKotlin` / `android.newDsl`.

## Resultado

Migración completada. Topología final (ver
[[ADR-0006 Estructura multi-módulo KMP para AGP 9]]):

- **`:shared`** — librería KMP (`com.android.kotlin.multiplatform.library` +
  `kotlin.multiplatform` + Compose), bloque `kotlin { android { … } }` +
  `jvm("desktop")`; contiene `App`, tema y el seam `platformName` (commonMain +
  androidMain/desktopMain). Namespace `im.gar.titanssh.shared`.
- **`:androidApp`** — `com.android.application` + Compose (sin
  `kotlin.android`: Kotlin va integrado en AGP 9). `MainActivity` en
  `im.gar.titanssh.android`, applicationId `im.gar.titanssh`.
- **`:desktopApp`** — `kotlin.jvm` + `compose.desktop.application`; entrypoint
  `im.gar.titanssh.desktop.MainKt`. Se fija Java y Kotlin a JVM 17 para evitar el
  error de JVM-target inconsistente (Java tomaba 21 del JBR).

Se eliminó el módulo `composeApp` y los flags deprecados. Sin deuda de estructura
ante AGP 9+.
