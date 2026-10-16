---
Nombre: "AppUpdater"
Tipo: "Servicio"
Área: "Plataforma"
Feature: "Distribución"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/update/AppUpdater.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.update.AppUpdates.updater"
Resumen: "Actualizador de la app (ADR-0025), uno por proceso en AppUpdates.updater. Expone state (UpdateState: Unavailable, Idle, Checking, UpToDate, Downloading, Ready, Installing, Failed) y recibe la política (UpdatePolicy de AppSettings), el número de pestañas abiertas y si la app está en pantalla. checkNow e installNow son las acciones del usuario; beforeExit lo llama el escritorio al cerrar la ventana y AppUpdates.exitApp lo fija main.kt. El motor común (UpdateEngine, jvmShared) verifica la firma Ed25519 del manifiesto con las claves de BuildInfo.UPDATE_KEYS; cada plataforma aporta un UpdatePlatform (MSI, tar.gz, deb y rpm en escritorio; PackageInstaller en Android). La UI está en UpdateStatus (Ajustes y Acerca de) y en el [^] de la cabecera."
Última modificación: 2026-10-16T10:30:00+02:00
---

# AppUpdater

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad:

- El contrato y los estados:
  [AppUpdater.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/update/AppUpdater.kt).
- El motor (comprobación, verificación, descargas que sobreviven a un reinicio y
  política de instalación):
  [UpdateEngine.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/update/UpdateEngine.kt).
- Las plataformas:
  [AppUpdater.desktop.kt](../../shared/src/desktopMain/kotlin/io/github/danielperezmartinez/titanssh/update/AppUpdater.desktop.kt),
  con sus scripts en `shared/src/desktopMain/resources/update/`, y
  [AppUpdater.android.kt](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/update/AppUpdater.android.kt).
- El manifiesto firmado que publica CI:
  [tools/release/update-manifest.sh](../../tools/release/update-manifest.sh) y
  las claves de confianza en `shared/update-keys.txt`.

Decisión en [[ADR-0025 Actualización automática de la app]]; tarea en
[[Actualización automática de la app]].
