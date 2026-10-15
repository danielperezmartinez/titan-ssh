---
Nombre: "SftpBrowser"
Tipo: "Servicio"
Área: "Conexión SSH"
Feature: "SFTP"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/sftp/SftpBrowser.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.sftp"
Resumen: "Estado de una pestaña SFTP (ADR-0021, ADR-0024), que sobrevive a sus reconexiones. SessionTab le presta un canal SshSftp por conexión con attach(sftp) y se lo quita con detach(sftp) al caer. Expone listing (SftpListing: carpeta, entradas, cargando, error), transfers (SftpTransfer de bajada o subida, de archivo o de carpeta con files/filesDone/skipped, en estado RUNNING, WAITING, DONE, FAILED o CANCELLED), busy (cambio en curso), message (SftpMessage del último cambio, con showError y clearMessage) y conflict (UploadConflict: nombre ocupado al subir, se contesta con answer(ConflictChoice, paraElResto)). Navega con open, up, goHome y refresh desde el directorio inicial de la sesión (SftpPaths.initial) o el de inicio. download(entry) baja un archivo, o una carpeta entera a una DownloadFolder (los enlaces a carpetas no se siguen; se cuentan como saltados). upload(fuentes) sube UploadSource a la carpeta en pantalla mediante un parcial .<nombre>.titan-part que renombra al terminar. createFolder, rename, move(entradas, destino) y delete(entradas) cambian el destino; delete borra carpetas de dentro hacia fuera y los enlaces como enlaces, y contentsOf(entradas) cuenta lo que hay dentro para la segunda confirmación. Todo corre en onChannel: si la conexión cae espera (WAITING) y sigue en el canal nuevo (una descarga desde el último byte escrito, una subida desde lo que tiene el servidor menos writeAhead, un cambio comprobando si el intento anterior se aplicó). Un SshSftpFailed (respuesta del servidor) o un fallo local lo terminan al momento; cualquier otro error se toma como conexión caída y avisa con el onLost de attach para reconectar ya; si vuelve 5 veces seguidas sin avanzar, falla. cancel borra lo escrito; una subida cancelada sin conexión queda en CANCELLING hasta que reconecta y borra su parcial (SftpTransfer.partial). partialUploads() dice qué parciales quedarían en el destino si se cierra ahora; close() (suspend) borra antes los parciales si hay canal. SftpPaths reúne la aritmética de rutas (Windows /C:/ incluido), nameProblem, el orden del listado y los formatos de tamaño y permisos."
Última modificación: "2026-10-15T11:00:00+02:00"
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
- [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]] y
  [[ADR-0024 Sesión SFTP con escritura, carpetas y selección múltiple]].
