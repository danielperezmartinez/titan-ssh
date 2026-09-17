---
Nombre: Estructura multi-módulo KMP para AGP 9
Número: 6
Estado: Aceptada
Resumen: 'La app se estructura en tres módulos Gradle: una librería KMP compartida (com.android.kotlin.multiplatform.library) y dos apps finas por plataforma (androidApp con com.android.application, desktopApp con kotlin.jvm). Evita los flags deprecados que AGP 9 obliga a usar cuando com.android.application y kotlin.multiplatform conviven en un mismo módulo.'
Decisión: Separar en :shared (librería KMP) + :androidApp + :desktopApp, sin ningún módulo que aplique a la vez un plugin de aplicación/ librería Android y kotlin.multiplatform.
Consecuencias: Cero deuda de estructura frente a AGP 9+ (sin android.builtInKotlin/newDsl); el código compartido (UI, dominio, expect/actual) vive en :shared y las apps solo aportan su entrypoint; AGP 9 trae Kotlin integrado en módulos Android, por lo que androidApp no aplica kotlin.android.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T18:55:00+02:00
Última modificación: 2026-09-17T18:55:00+02:00
---

# ADR-0006 · Estructura multi-módulo KMP para AGP 9

## Contexto

Refina la topología de módulos de [[ADR-0002 Stack KMP y alcance multiplataforma]]
(no cambia el stack). Al actualizar a AGP 9 (ver la tarea
[[Inicializar repositorio y esqueleto KMP]] y su actualización de toolchain),
apareció un cambio incompatible: **desde AGP 9.0, `com.android.application` (o
`com.android.library`) ya no puede convivir con `org.jetbrains.kotlin.multiplatform`
en un mismo módulo.** El módulo único solo compilaba con los flags
`android.builtInKotlin=false` / `android.newDsl=false`, ya marcados como
**deprecados**: deuda técnica con caducidad.

El proyecto arranca de cero, así que reestructurar ahora es barato; hacerlo con
código real más adelante sería costoso.

## Decisión

Adoptar la topología de tres módulos nativa de AGP 9 para apps KMP + Compose:

1. **`:shared`** — librería KMP con `com.android.kotlin.multiplatform.library` +
   `org.jetbrains.kotlin.multiplatform` + Compose. Bloque `kotlin { android { … } }`
   (namespace, compileSdk, minSdk, `compilerOptions`) y target `jvm("desktop")`.
   Contiene `commonMain` (UI + dominio + `expect`), `androidMain` y `desktopMain`
   (`actual`).
2. **`:androidApp`** — aplicación Android con `com.android.application` + Compose.
   Consume `:shared`. **No** aplica `org.jetbrains.kotlin.android`: AGP 9 trae
   Kotlin integrado en módulos Android.
3. **`:desktopApp`** — aplicación de escritorio con `org.jetbrains.kotlin.jvm` +
   Compose (`compose.desktop.application`). Consume `:shared`. Alinea el target
   de Java y Kotlin (ambos 17) para evitar el error de JVM-target inconsistente.

Ningún módulo aplica a la vez un plugin de aplicación/librería Android y
`kotlin.multiplatform`, así que no hacen falta flags de compatibilidad.

## Alternativas consideradas

- **Módulo único con `android.builtInKotlin=false` / `android.newDsl=false`** —
  funciona pero usa un puente deprecado que desaparecerá en un AGP futuro; se
  descarta por dejar deuda.
- **Quedarse en AGP 8.x** (sin la incompatibilidad) — renuncia a estar en la
  última versión y a compilar contra API 37; se descarta por preferencia
  explícita de estar al día.

## Consecuencias

- Positivas: sin deuda de estructura ante AGP 9+; separación limpia entre código
  compartido (`:shared`) y entrypoints por plataforma; el patrón escala al añadir
  más superficies o, en el futuro, otros targets.
- Negativas / compromisos: más módulos y algo más de ceremonia en los
  `build.gradle.kts`; hay que recordar que Android ya no lleva `kotlin.android` y
  que el target JVM se fija por módulo.
