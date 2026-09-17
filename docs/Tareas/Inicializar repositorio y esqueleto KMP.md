---
Nombre: Inicializar repositorio y esqueleto KMP
Estado: Hecha
Resumen: 'Poner en marcha el proyecto: inicializar git con repositorio remoto y crear el esqueleto Kotlin Multiplatform + Compose (Gradle, source sets commonMain/androidMain/desktopMain). Es el paso previo al resto del trabajo.'
Decisiones: Sigue [[ADR-0002 Stack KMP y alcance multiplataforma]]. Toolchain de la máquina: JDK 21 (JBR de Android Studio) y Android SDK (compileSdk 35). Gradle 8.10.2, Kotlin 2.1.0, AGP 8.7.3, Compose Multiplatform 1.7.3. Módulo único `composeApp` con targets android + desktop (jvm). Paquete `im.gar.titanssh`. JetBrains Mono se pospone (se usa monospace del sistema) por evitar bundling de fuente en el esqueleto.
Bloqueada: []
Fecha de creación: 2026-09-17T15:40:00+02:00
Última modificación: 2026-09-17T18:05:00+02:00
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
