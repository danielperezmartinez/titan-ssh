# titan-ssh

Cliente SSH multiplataforma (Android + escritorio Windows/Linux) construido con
**Kotlin Multiplatform + Compose Multiplatform**. Alternativa a Termius centrada
en sesiones que sobreviven a los microcortes de red y en automatización por
sesión sin muros de pago.

> La fuente de verdad del proyecto (reglas, decisiones, tareas) vive en la bóveda
> [`docs/`](docs/README.md). Cualquier agente o persona debe leerla antes de
> trabajar.

## Estructura

- `composeApp/` — módulo único con la app.
  - `src/commonMain` — dominio y UI compartida (Compose Multiplatform) + interfaces `expect`.
  - `src/androidMain` — `actual` de Android y empaquetado de la app Android.
  - `src/desktopMain` — `actual` y entrypoint de escritorio (JVM, Windows/Linux).

Ver [ADR-0002](docs/Decisiones/ADR-0002%20Stack%20KMP%20y%20alcance%20multiplataforma.md)
para el stack y el alcance.

## Requisitos

- **JDK 17+** (el proyecto se ha inicializado con el JBR 21 de Android Studio).
- **Android SDK** con `compileSdk 35` y build-tools 35 (ruta en `local.properties`).

## Comandos

```bash
# Escritorio: ejecutar la app
./gradlew :composeApp:run

# Android: construir el APK de debug
./gradlew :composeApp:assembleDebug

# Empaquetado nativo de escritorio (Windows .msi / Linux .deb)
./gradlew :composeApp:packageDistributionForCurrentOS
```

## Idioma

Código, identificadores y comentarios en **inglés**; la documentación de `docs/`
en **español** (convención del proyecto).
