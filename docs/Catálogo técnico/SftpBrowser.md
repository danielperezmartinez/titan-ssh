---
Nombre: "SftpBrowser"
Tipo: "Servicio"
Área: "Conexión SSH"
Feature: "SFTP"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/SftpBrowser.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.sftp"
Resumen: "Estado de una pestaña SFTP (ADR-0021), que sobrevive a sus reconexiones. SessionTab le presta un canal SshSftp por conexión con attach(sftp) y se lo quita con detach(sftp) al caer. Expone listing (SftpListing: carpeta, entradas, cargando, error) y transfers (SftpTransfer: progreso y estado RUNNING, WAITING, DONE, FAILED o CANCELLED). Navega con open, up, goHome y refresh, y empieza en el directorio inicial de la sesión (SftpPaths.initial) o en el de inicio. download(entry) escribe por bloques en un DownloadTarget; si la conexión cae, la descarga espera (WAITING) y sigue en el canal nuevo desde el último byte escrito, salvo que el archivo haya cambiado de tamaño. Un SshSftpFailed (respuesta del servidor) o un fallo al guardar la terminan al momento; cualquier otro error (p. ej. el tiempo de espera de sshj en un enlace muerto) se toma como conexión caída y avisa a la pestaña con el onLost de attach para que reconecte ya; si vuelve 5 veces seguidas sin avanzar, falla. cancel borra el archivo a medias. Solo lectura. SftpPaths reúne la aritmética de rutas (Windows /C:/ incluido), el orden del listado y los formatos de tamaño y permisos. startNote se muestra con la carpeta inicial (SftpListing.notice), igual que el aviso de una carpeta inicial que no se puede abrir."
Última modificación: "2026-10-14T13:00:00+02:00"
---

# SftpBrowser

Después de descubrir esta pieza en el catálogo, consulta
[SftpBrowser.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/SftpBrowser.kt)
y [SftpPaths.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/SftpPaths.kt)
como fuente de verdad (cubiertos por `SftpBrowserTest` y `SftpPathsTest`).

## Relaciones

- Usa el `SshSftp` de [[SshConnector]] y guarda con [[DownloadStore]].
- Lo crea y alimenta `SessionTab` para las sesiones `SessionType.SFTP`; lo
  muestra [[SftpView]].
- [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]].
