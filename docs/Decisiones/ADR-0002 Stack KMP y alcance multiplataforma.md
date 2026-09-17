---
Nombre: Stack Kotlin Multiplatform + Compose y alcance multiplataforma
Número: 2
Estado: Aceptada
Resumen: titan-ssh se construye con Kotlin Multiplatform + Compose Multiplatform, con un único código base para Android y escritorio (JVM en Windows y Linux). Build con Gradle.
Decisión: Adoptar Kotlin Multiplatform + Compose Multiplatform (Android + escritorio JVM) con Gradle, y fijar el alcance en Linux, Windows y Android.
Consecuencias: La lógica compartida vive en commonMain y el acceso nativo se resuelve con expect/actual; el gestor de paquetes es Gradle.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T15:40:00+02:00
Última modificación: 2026-09-17T17:01:03+02:00
---

# ADR-0002 · Stack Kotlin Multiplatform + Compose y alcance multiplataforma

## Contexto

La idea nació como app Android, pero el alcance objetivo es multiplataforma:
**Linux, Windows y Android**. Hace falta un stack que comparta el máximo de
código entre móvil y escritorio sin renunciar a un buen acceso a los almacenes
de secretos nativos (crítico por [[ADR-0001 Credenciales en almacén nativo del SO]])
ni a un rendimiento de terminal razonable.

## Decisión

Construir titan-ssh con **Kotlin Multiplatform (KMP) + Compose Multiplatform**:

- Un único código base para **Android** y **escritorio JVM** (Windows y Linux).
  **Fuera de alcance expresamente: macOS e iOS.**
- **Gradle** como build y gestor de dependencias (con version catalogs).
- Lógica de dominio y UI compartidas en `commonMain`; lo específico de cada
  plataforma en sus source sets, resolviendo el acceso nativo con
  `expect`/`actual`.

## Alternativas consideradas

- **Flutter** — un solo código para las tres plataformas, pero el acceso a los
  almacenes de secretos y detalles nativos exige plugins/FFI; se prefiere el
  ecosistema JVM de KMP por su madurez en SSH y por integrar Kotlin nativo.
- **App solo Android (Kotlin + Compose)** — descartada al ampliarse el alcance a
  escritorio.

## Consecuencias

- Positivas: máximo código compartido entre Android y escritorio; ecosistema SSH
  maduro en JVM; acceso nativo por plataforma vía `expect`/`actual`.
- Negativas / compromisos: el empaquetado de escritorio (Windows/Linux) y la
  librería SSH concreta quedan por afinar; hay que mantener la disciplina de no
  filtrar código de plataforma a `commonMain`.
