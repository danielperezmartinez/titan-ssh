---
Nombre: "DownloadStore"
Tipo: "Implementación de plataforma"
Área: "Plataforma"
Feature: "SFTP"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/DownloadStore.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.sftp"
Resumen: "Dónde se guardan las descargas (ADR-0021), con expect fun createDownloadStore(). create(nombre) crea un archivo nuevo sin sobrescribir nada (nombre (1).ext…) y devuelve un DownloadTarget: write por bloques, complete lo cierra y lo hace visible, discard lo borra, open lo abre con la app del sistema; name y location dicen cómo quedó y dónde buscarlo. Llamadas bloqueantes. Android 10+ usa la colección Downloads de MediaStore (sin permisos, IS_PENDING hasta completar, renombra él mismo); Android 8–9 la carpeta Download propia de la app (sin abrir). Escritorio usa la carpeta de descargas del usuario (XDG en Linux, ~/Downloads en otros) y abre con Desktop o xdg-open. La carpeta es fija hasta la tarea de ajustes generales."
Última modificación: "2026-10-14T12:00:00+02:00"
---

# DownloadStore

Después de descubrir esta pieza en el catálogo, consulta
[DownloadStore.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/DownloadStore.kt)
y sus `actual` de
[Android](../../shared/src/androidMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/DownloadStore.android.kt)
y [escritorio](../../shared/src/desktopMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/DownloadStore.desktop.kt)
como fuente de verdad.

## Relaciones

- La usa [[SftpBrowser]].
- Elegir otra carpeta: [[Pantalla de ajustes generales de la app]].
