---
Nombre: Inicializar repositorio y esqueleto KMP
Estado: Hecha
Resumen: 'Poner en marcha el proyecto: inicializar git con repositorio remoto y crear el esqueleto Kotlin Multiplatform + Compose (Gradle, source sets commonMain/androidMain/desktopMain). Es el paso previo al resto del trabajo.'
Decisiones: Sigue [[ADR-0002 Stack KMP y alcance multiplataforma]]. Toolchain de la máquina: JDK 21 (JBR de Android Studio) y Android SDK (compileSdk 35). Gradle 8.10.2, Kotlin 2.1.0, AGP 8.7.3, Compose Multiplatform 1.7.3. Módulo único `composeApp` con targets android + desktop (jvm). Paquete `im.gar.titanssh`. JetBrains Mono se pospone (se usa monospace del sistema) por evitar bundling de fuente en el esqueleto.
Bloqueada: []
Fecha de creación: 2026-09-17T15:40:00+02:00
Última modificación: 2026-09-17T18:35:00+02:00
---

# Inicializar repositorio y esqueleto KMP

## Objetivo

Dejar el proyecto listo para desarrollar: control de versiones y estructura base
del stack acordado. El directorio aún no es un repositorio git.

## Criterios de finalización

- Repositorio git inicializado y publicado en un remoto.
- Proyecto Gradle de Kotlin Multiplatform + Compose Multiplatform con los source
  sets `commonMain`, `androidMain` y `desktopMain` (JVM Windows/Linux).
- Compila y arranca una app mínima en Android y en escritorio.
- Convención de idioma aplicada (código en inglés).

## Verificación

Toolchain de la máquina: JDK 21 (JBR de Android Studio) + Android SDK
(`compileSdk 35`, build-tools 35). Build con el wrapper (`./gradlew`) y
`JAVA_HOME` al JBR.

- **Android:** `:composeApp:assembleDebug` → `BUILD SUCCESSFUL`; genera
  `composeApp/build/outputs/apk/debug/composeApp-debug.apk` (~8,7 MB).
- **Escritorio:** `:composeApp:compileKotlinDesktop` + `:composeApp:run` →
  la ventana Compose arranca sin excepciones (verificado y cerrado).
- **expect/actual:** `platformName()` resuelve su `actual` en `androidMain` y
  `desktopMain`; ambos source sets compilan.
- **Idioma:** todo el código, identificadores y comentarios en inglés.

Pendiente de verificar por el usuario (opcional): instalar y abrir el APK en un
emulador/dispositivo, y el empaquetado nativo de escritorio
(`packageDistributionForCurrentOS`).

## Resultado

Esqueleto KMP + Compose Multiplatform en el módulo único `composeApp` con
`commonMain` / `androidMain` / `desktopMain`, paquete `im.gar.titanssh`.

- Gradle 8.10.2 (wrapper), version catalog: Kotlin 2.1.0, AGP 8.7.3, Compose
  Multiplatform 1.7.3; target de bytecode JVM 17.
- UI compartida mínima que aplica los tokens dark-first (mapeados a Material 3):
  `TitanColors` + `TitanTheme` (mono en todo, marcadores ASCII, superficies
  planas). JetBrains Mono se pospone: se usa el monospace del sistema.
- git inicializado (rama `main`), `.gitignore` + `.gitattributes` (gradlew en
  LF), commit inicial `ec866f2`, publicado en el remoto
  `https://github.com/danielperezmartinez/titan-ssh` (`origin/main`).

Nota: no se crean entradas en el Catálogo técnico todavía; su taxonomía (Tipo,
Área, Feature, Ámbito) sigue **por definir** y el README prohíbe inventar
valores. Las superficies reutilizables (`TitanTheme`, `TitanColors`, `App`,
seam `platformName`) se catalogarán al acordar el vocabulario.

Remoto indicado por el usuario y `git push -u origin main` correcto (las
credenciales ya estaban configuradas en la máquina). Todos los criterios de
finalización cumplidos y verificados.

## Actualización (2026-09-17): toolchain a Android 17 / AGP 9

A petición del usuario (preferencia por estar a la última; su móvil es Android
17) se subió todo el stack. El esqueleto y las decisiones no cambian.

- **Versiones:** Gradle 8.10.2 → **9.7.1**; AGP 8.7.3 → **9.4.0**; Kotlin 2.1.0 →
  **2.4.20**; Compose Multiplatform 1.7.3 → **1.9.3**;
  `compileSdk`/`targetSdk` 35 → **37** (Android 17). `minSdk` sigue en **26**
  (retrocompatibilidad; "estar a la última" y "soportar móviles antiguos" son
  ajustes distintos y no chocan). `activity-compose` 1.9.3 → 1.13.0.
- **SDK instalado** por CLI: `platforms/android-37.0` (API 37, Android 17) y
  `build-tools/37.0.0`. La nueva Android CLI (`android sdk ...`, reemplaza a
  `sdkmanager`) crashea al salir (exit `0xC0000409`) pero completa el trabajo;
  hay que verificar la instalación aparte, no fiarse del código de salida.
- **AGP 9 — cambio relevante:** desde AGP 9.0 `com.android.application` y
  `org.jetbrains.kotlin.multiplatform` no conviven en un mismo módulo. Se
  mantiene el módulo único con `android.builtInKotlin=false` /
  `android.newDsl=false` en `gradle.properties`. Es un puente **soportado pero
  ya marcado como deprecado**; la alternativa futura es separar en multi-módulo
  (librería `com.android.kotlin.multiplatform.library` + app Android). Candidato
  a ADR/tarea propia si se decide migrar.
- **Verificado de nuevo:** `:composeApp:assembleDebug` → `BUILD SUCCESSFUL`
  (APK ~10,4 MB) y `:composeApp:run` arranca la ventana sin excepciones, ambos
  con el stack nuevo.
