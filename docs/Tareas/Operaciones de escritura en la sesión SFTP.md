---
Nombre: 'Operaciones de escritura en la sesión SFTP'
Estado: 'En curso'
Resumen: 'Petición del usuario (2026-10-14), alcance cerrado el 2026-10-15. La sesión SFTP nace de solo lectura (navegar y descargar). Esta tarea añade subir archivos (reanudables y preguntando qué hacer si el nombre ya existe), crear carpetas, renombrar, borrar (carpetas con contenido con doble confirmación), descargar carpetas enteras como carpeta y un modo de selección múltiple por pulsación larga para descargar, mover y borrar varios elementos a la vez. Cambiar permisos queda fuera.'
Decisiones: 'Sale de [[Sesión SFTP para navegar y descargar archivos]] y de [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]], que limitan la primera versión a solo lectura. El diseño está en [[ADR-0024 Sesión SFTP con escritura, carpetas y selección múltiple]].'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-15T12:30:00+02:00
---

# Operaciones de escritura en la sesión SFTP

## Objetivo

Completar el explorador SFTP con las operaciones que modifican el destino y
con la selección de varios elementos a la vez.

## Alcance

Decidido con el usuario el 2026-10-15:

- **Subir** uno o varios archivos (en Android con el selector de documentos).
  Las subidas se reanudan tras un corte, igual que las descargas. Si en la
  carpeta ya hay un archivo con ese nombre se pregunta cada vez: reemplazar,
  conservar ambos (`nombre (1).ext`) u omitir, con la opción de aplicar la
  respuesta al resto de la tanda.
- **Crear carpeta** y **renombrar**.
- **Borrar**, con confirmación. Una carpeta con contenido se borra con
  **doble confirmación**: primero la pregunta rápida y, si se contesta que sí,
  un aviso más serio que explica lo que va a pasar (cuántos archivos y
  carpetas se borran para siempre) y pide un segundo sí.
- **Descargar una carpeta entera** como carpeta: en Descargas aparece la
  carpeta con su nombre y su estructura, no solo su contenido.
- **Modo selección**: una pulsación larga sobre un elemento entra en el modo
  selección; se marcan varios archivos o carpetas y se aplican a la vez
  **Descargar**, **Mover a otra carpeta** (se navega hasta el destino y se
  pulsa «Mover aquí») y **Borrar**, con atajos para seleccionar todo o nada.
- **Cambiar permisos: no** (descartado por el usuario).

## Criterios de finalización

- Cada operación funciona contra destinos Unix y Windows.
- Probado en el emulador y en escritorio.

## Verificación

Avance del 2026-10-15, en el worktree `sftp-write` (sin commit todavía):

- `SftpBrowserTest`: 22 tests en verde, 11 de ellos nuevos (subida con
  parcial, reanudación con margen `writeAhead`, conflictos con y sin «para
  el resto», crear, renombrar, mover, borrar sin seguir enlaces, contar
  contenido, descargar carpeta y cancelarla).
- `SshjSftpIntegrationTest` (opt-in) en verde contra el sshd de pruebas
  Linux y contra Win32-OpenSSH en Windows: `writeFrom` desde un
  desplazamiento, `makeDirectory`, `removeDirectory`, `rename` y `remove`
  con `SshSftpFailed`.
- `:shared:desktopTest` completo: 419 de 420. Falla solo
  `DesktopSecretStoreTest` porque la sesión corría en la sesión 0 de Windows,
  sin Administrador de credenciales (ambiental, no relacionado).
- Emulador `Pixel_9_Pro_XL` contra `titan-test-sshd`: modo selección,
  descarga múltiple (carpeta con estructura, subcarpeta vacía y enlace a
  carpeta saltado), subida con conflicto «Conservar ambos» para los dos
  archivos, subida de 300 MB con «Reemplazar» cortando la conexión a mitad
  (se reanudó sola y el SHA-256 coincide), crear carpeta, renombrar, mover
  (con el rechazo de meter una carpeta en sí misma), borrar con doble
  confirmación (el destino del enlace queda intacto), carpeta vacía con una
  sola confirmación y archivo desde su fila.
- La prueba en el emulador encontró dos fallos, ya corregidos: una
  recursión infinita al crear archivos dentro de una carpeta descargada en
  Android, que cerraba la app; y la bandeja mostraba el nombre pedido en vez
  del que MediaStore da al publicar (`nota (1).txt`).
- Cancelar subidas sin conexión (decidido el 2026-10-15, ver ADR-0024): 4
  tests nuevos (26 en total) y prueba en el emulador con el modo avión.
  Cancelar sin red deja la subida en «cancelando» con el aviso ámbar y, al
  quitar el modo avión, borra el parcial; el `grande.bin` original sigue
  intacto. Cerrar la pestaña sin red pregunta, nombrando el parcial y la
  carpeta. Cerrarla con red a mitad de una subida borra el parcial sin
  preguntar. La pestaña solo deja de reintentar a los 15 minutos (sin contar
  el tiempo sin red del sistema), así que el aviso sale mientras no haya
  conexión, no solo cuando la pestaña se rinde.
- Pendiente: que el usuario lo pruebe en el emulador y en escritorio (el
  diálogo de archivos de escritorio no se ha podido ver desde la sesión 0).

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
