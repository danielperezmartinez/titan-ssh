---
Nombre: 'Cambiar el identificador de la app a io.github'
Estado: 'Pendiente'
Resumen: 'Sustituir im.gar.titanssh (gar.im no es del usuario y no quiere usarlo) por io.github.danielperezmartinez.titanssh en todas partes: applicationId y namespaces de Android, paquetes Kotlin (unos 100 ficheros bajo im/gar/titanssh), mainClass de escritorio, paquete de Res de Compose, alias del Keystore y referencias en el agente Go. Tiene que hacerse antes de la primera versión pública, porque en Android el applicationId no se puede cambiar sin perder las actualizaciones. Gratis: el ID io.github.* se verifica en Flathub con la cuenta de GitHub, sin comprar dominio.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §2.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Cambiar el identificador de la app a io.github

## Objetivo

Que el identificador de la app no dependa de `gar.im` y sea verificable gratis
en Flathub, dejándolo fijado antes de publicar nada.

## Contexto (inventario de 2026-09-23)

- `androidApp/build.gradle.kts`: `applicationId = "im.gar.titanssh"`,
  `namespace = "im.gar.titanssh.android"`.
- `shared/build.gradle.kts`: `namespace = "im.gar.titanssh.shared"` y
  `packageOfResClass = "im.gar.titanssh.resources"`.
- `desktopApp/build.gradle.kts`: `mainClass = "im.gar.titanssh.desktop.MainKt"`.
- **Unos 100 ficheros `.kt`/`.kts`** con `package`/`import im.gar.titanssh...`,
  con los directorios `im/gar/titanssh` en todos los source sets
  (commonMain, jvmShared, androidMain, desktopMain y tests).
- `AndroidSecretStore.MASTER_KEY_ALIAS = "im.gar.titanssh.secretstore.master"`:
  cambiarlo hace inaccesibles las claves ya creadas en el Keystore. Solo afecta a
  las instalaciones de desarrollo del usuario (la app nueva será además otro
  `applicationId`, es decir, otra app con datos vacíos), así que se puede
  cambiar sin migración; confirmarlo al hacerlo.
- Comentarios del agente Go que apuntan a la ruta de `AgentProtocol.kt`
  (`agent/internal/protocol/protocol.go`, `agent/README.md`).
- Los nombres visibles y los directorios de datos (`titan-ssh`) **no** llevan el
  dominio y no cambian.

## Criterios de finalización

- ID Android: `io.github.danielperezmartinez.titanssh` (los `applicationId` no
  admiten `-`). Paquete Kotlin raíz: `io.github.danielperezmartinez.titanssh`.
- **ID de Flatpak**: comprobar la regla vigente de Flathub para `io.github.*`
  (debe corresponder a `io.github.<usuario>.<repo>`; el repositorio se llama
  `titan-ssh` y los `-` suelen pasar a `_`). Anotarlo aquí y usarlo en
  [[Canal Linux Flatpak en Flathub]].
- Renombrado completo con un solo commit mecánico (mover directorios +
  reescribir `package`/`import`/KDoc), sin mezclarlo con otros cambios.
- **Coordinación**: hacerlo con el árbol de trabajo limpio y sin otras sesiones
  tocando el código a la vez; un renombrado de paquetes choca con cualquier
  cambio en curso.
- Build y tests verdes (desktopTest, tests del agente Go) y la app arranca en
  escritorio y en el Pixel ([[ssh-test-host]]).
- Actualizar las rutas de código citadas en la bóveda (Catálogo técnico y
  tareas vivas) que apunten a `im/gar/titanssh`.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
