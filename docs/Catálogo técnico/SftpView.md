---
Nombre: "SftpView"
Tipo: "Componente UI"
Área: "Terminal"
Feature: "SFTP"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/SftpView.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Pestaña de una sesión SFTP (ADR-0021). Arriba, los avisos de clave de host y la franja de estado con reconectar, como en el mouse pad. Debajo, la barra de ruta: [^] subir, [~] inicio, las partes de la ruta pulsables, [/] escribir una ruta, [.] mostrar ocultos y [r] refrescar. El listado pone carpetas primero ([d], [@] para enlaces) y archivos con tamaño y fecha; tocar una carpeta la abre y tocar un archivo despliega sus datos y Descargar. Abajo, la bandeja de descargas con progreso, espera de conexión, cancelar, abrir al tocar una terminada y quitarla de la lista."
Última modificación: "2026-10-14T12:00:00+02:00"
---

# SftpView

Después de descubrir esta pieza en el catálogo, consulta
[SftpView.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/SftpView.kt)
como fuente de verdad.

## Relaciones

- Muestra el estado de [[SftpBrowser]]; usa los componentes de
  [[Componentes UI compartidos]] y las barras de clave de host de
  [[TerminalView]].
- La elige `SessionsArea` para las pestañas de tipo SFTP.
