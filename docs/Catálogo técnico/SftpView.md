---
Nombre: "SftpView"
Tipo: "Componente UI"
Área: "Terminal"
Feature: "SFTP"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/SftpView.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Pestaña de una sesión SFTP (ADR-0021, ADR-0024). Arriba, los avisos de clave de host y la franja de estado con reconectar, como en el mouse pad. Debajo, la barra de ruta: [^] subir, [~] inicio, las partes de la ruta pulsables, [/] escribir una ruta, [.] mostrar ocultos y [r] refrescar. El listado pone carpetas primero ([d], [@] para enlaces) y archivos con tamaño y fecha; tocar una carpeta la abre y tocar un archivo despliega sus datos con Descargar, Renombrar y Borrar (confirmación en la fila). Una pulsación larga (clic derecho en escritorio) entra en el modo selección: marcadores [x]/[ ], la barra de ruta pasa a «N seleccionados» con todo/ninguno, y la barra inferior ofrece Descargar, Mover (se navega al destino y «Mover aquí»), Renombrar con uno solo y Borrar. Borrar pregunta, y si hay carpetas con contenido muestra un segundo aviso serio con lo que se borra. Fuera de la selección la barra inferior ofrece [+] Carpeta y [^] Subir archivos (selector del sistema, rememberUploadPicker). Encima, la línea de lo que se está haciendo o del resultado y el panel del nombre ocupado al subir (Reemplazar, Conservar ambos, Omitir, para el resto). Abajo, la bandeja de transferencias (se desplaza a la última) con progreso, espera de conexión, cancelar, abrir al tocar una descarga terminada y quitarla de la lista; una subida «cancelando» sin conexión avisa en ámbar del parcial que quedaría. SessionsArea pide confirmación al cerrar sin conexión una pestaña SFTP con subidas a medias."
Última modificación: "2026-10-15T11:00:00+02:00"
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
