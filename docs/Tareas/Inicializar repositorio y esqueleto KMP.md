---
Nombre: Inicializar repositorio y esqueleto KMP
Estado: En curso
Resumen: 'Poner en marcha el proyecto: inicializar git con repositorio remoto y crear el esqueleto Kotlin Multiplatform + Compose (Gradle, source sets commonMain/androidMain/desktopMain). Es el paso previo al resto del trabajo.'
Decisiones: Sigue [[ADR-0002 Stack KMP y alcance multiplataforma]]. Toolchain de la máquina: JDK 21 (JBR de Android Studio) y Android SDK (compileSdk 35). Gradle 8.10.2, Kotlin 2.1.0, AGP 8.7.3, Compose Multiplatform 1.7.3. Módulo único `composeApp` con targets android + desktop (jvm). Paquete `im.gar.titanssh`. JetBrains Mono se pospone (se usa monospace del sistema) por evitar bundling de fuente en el esqueleto.
Bloqueada: []
Fecha de creación: 2026-09-17T15:40:00+02:00
Última modificación: 2026-09-17T17:35:00+02:00
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

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
