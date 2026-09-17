# titan-ssh

Cliente SSH multiplataforma (Android + escritorio Windows/Linux) construido con
**Kotlin Multiplatform + Compose Multiplatform**. Alternativa a Termius centrada
en sesiones que sobreviven a los microcortes de red y en automatización por
sesión sin muros de pago.

> La fuente de verdad del proyecto (reglas, decisiones, tareas) vive en la bóveda
> [`docs/`](docs/README.md). Cualquier agente o persona debe leerla antes de
> trabajar.

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
```

## Idioma

Código, identificadores y comentarios en **inglés**; la documentación de `docs/`
en **español** (convención del proyecto).
