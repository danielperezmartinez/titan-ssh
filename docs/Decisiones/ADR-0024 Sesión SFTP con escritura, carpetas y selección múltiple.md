---
Nombre: 'Sesión SFTP con escritura, carpetas y selección múltiple'
Número: 24
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-15. Amplía ADR-0021 sin cambiar nada de lo que decidió: aquella dejó la escritura para una segunda fase y esta la define. El explorador SFTP deja de ser de solo lectura. Sube archivos con subidas reanudables y pregunta qué hacer si el nombre ya existe. Crea carpetas, renombra y borra; una carpeta con contenido pide doble confirmación. Descarga carpetas enteras como carpeta. Tiene un modo de selección por pulsación larga para descargar, mover y borrar varios elementos a la vez. Cambiar permisos queda fuera. Lo demás de ADR-0021 se mantiene.'
Decisión: 'El explorador SFTP sube, crea carpetas, renombra, mueve y borra en el destino, descarga carpetas como carpetas y trabaja con varios elementos a la vez en un modo de selección. Borrar pide confirmación, y dos si hay carpetas con contenido.'
Consecuencias: 'SshSftp crece con writeFrom (escritura desde un desplazamiento), makeDirectory y removeDirectory. DownloadStore crece con createFolder (DownloadFolder) y aparece UploadSource con su selector por plataforma. Una subida usa un archivo parcial oculto junto a su destino y lo renombra al terminar. Cancelar sin conexión espera a la conexión para borrar el parcial, avisando de lo que quedaría; cerrar la pestaña sin conexión con subidas a medias pide confirmación. Los enlaces a carpetas nunca se siguen al borrar ni al descargar una carpeta.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-15T11:00:00+02:00
Última modificación: 2026-10-15T13:00:00+02:00
---

# ADR-0024 · Sesión SFTP con escritura, carpetas y selección múltiple

## Contexto

[[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]] creó la
sesión SFTP para navegar y descargar archivos sueltos, y dejó la escritura
para [[Operaciones de escritura en la sesión SFTP]]. El 2026-10-15 el usuario
cerró el alcance de esa tarea y añadió dos peticiones: elegir varios elementos
con una pulsación larga para trabajar con todos a la vez, y descargar una
carpeta entera **como carpeta**, no solo su contenido.

Esta ADR **amplía** la ADR-0021, no la sustituye: todo lo que aquella decidió
sigue vigente. Su punto 3 hizo de solo lectura la primera versión y dejó la
escritura para esta tarea; esta ADR la define. La descarga de carpetas sigue
las reglas de destino del punto 4, y las subidas copian el esquema de
reanudación del punto 5.

## Decisión

1. **Subir archivos.** El botón «Subir archivos» abre el selector del sistema:
   el de documentos en Android y el diálogo de archivos en escritorio, ambos
   con varios archivos a la vez. Los archivos van a la carpeta que se está
   viendo.
   - **Nombre ocupado:** antes de subir se pregunta qué hacer con cada nombre
     que ya existe en la carpeta: *Reemplazar*, *Conservar ambos* (se sube
     como `nombre (1).ext`) u *Omitir*. Se puede marcar «Hacer lo mismo con
     los siguientes» para el resto de la tanda. Si el nombre se ocupa mientras
     se sube y no se eligió reemplazar, el archivo toma el primer
     `nombre (n)` libre.
   - **Archivo parcial:** el archivo se escribe en `.<nombre>.titan-part`
     junto a su destino y se renombra al terminar. Así nadie ve medio archivo
     con su nombre real, y un reemplazo no destruye el original hasta que el
     nuevo está entero.
   - **Reanudable:** si la conexión cae, la subida espera como las descargas
     y sigue en el canal nuevo desde lo que tiene el servidor, retrocediendo
     lo que podía estar en vuelo (varias escrituras viajan a la vez y la
     última parte puede tener huecos).
   - **Cancelar siempre limpia** (decidido con el usuario el 2026-10-15):
     una subida cancelada solo se da por cancelada cuando su parcial está
     borrado. Si al cancelar no hay conexión, queda «cancelando» y lo borra
     en cuanto la pestaña reconecta. Mientras no haya conexión, la bandeja
     avisa en ámbar de qué archivo quedaría y en qué carpeta si la conexión
     no vuelve. Si la pestaña ha dejado de reintentar, añade que se pulse
     «reconectar» para borrarlo.
   - **Cerrar la pestaña:** con conexión, el cierre borra antes los
     parciales de las subidas sin terminar. Sin conexión, la `[x]` pregunta
     primero, diciendo qué archivos quedarán y dónde («Cerrar igualmente» o
     «No»). Una subida que falla intenta borrar su parcial en el momento y,
     si no puede, lo dice en su error.
   - Se descartó guardar la lista de parciales pendientes para borrarlos al
     reconectar en otra ocasión: complica la app y el usuario prefiere que el
     borrado ocurra siempre en el camino normal o que se le avise.
2. **Crear carpeta y renombrar.** «Carpeta» crea una en la carpeta que se está
   viendo. Renombrar se hace desde las acciones de un archivo o desde la
   selección con un solo elemento. Un nombre que ya existe se rechaza: no se
   pisa nada.
3. **Borrar.** Siempre se pregunta antes (`¿Borrar …?`). Si entre lo elegido
   hay carpetas, al contestar que sí se mira lo que contienen. Si alguna tiene
   contenido, sale un **segundo aviso más serio**: dice cuántos archivos y
   subcarpetas hay dentro y cuánto ocupan, que se borra todo, que SFTP no tiene
   papelera y que no se puede deshacer. Solo se borra con un segundo sí. Una
   carpeta se borra de dentro hacia fuera. **Un enlace se borra como enlace,
   sin seguirlo**: borrar un enlace a una carpeta no toca la carpeta a la que
   apunta.
4. **Descargar una carpeta como carpeta.** En Descargas aparece una carpeta con
   el nombre de la remota (`nombre (1)` si ya existe) y su misma estructura,
   subcarpetas vacías incluidas donde la plataforma las admite. Primero se
   recorre entera (para saber cuántos archivos y bytes son) y luego se bajan
   los archivos de uno en uno, cada uno reanudable como cualquier descarga. En
   la bandeja es una sola entrada con «n de N archivos» y el progreso total.
   - Los **enlaces a carpetas no se siguen** (pueden formar bucles o llevar
     fuera de la carpeta); los enlaces a archivos se descargan como archivos.
     Los enlaces a carpetas, los dispositivos y los archivos que el servidor
     no deja leer se saltan y se cuentan en la entrada de la bandeja.
   - Si se cancela o falla, se borra lo que ya había escrito, también los
     archivos ya terminados.
   - En Android 10+ (MediaStore) los archivos llevan la carpeta en
     `RELATIVE_PATH`. MediaStore no tiene carpetas vacías y la app no puede
     listar toda la carpeta Descargas, así que las carpetas vacías y la
     comprobación de si un nombre está ocupado se hacen sobre el
     almacenamiento compartido y son lo mejor posible: en el peor caso, la
     descarga entra en una carpeta que ya existía y sus archivos se numeran,
     nunca se sobrescriben.
5. **Modo selección.** Una pulsación larga sobre un archivo o una carpeta
   (clic derecho en escritorio) entra en el modo selección, con ese elemento
   ya marcado. En ese modo cada toque marca o desmarca, la barra de ruta pasa a
   decir cuántos hay seleccionados (con «todo» / «ninguno» y `[x]` para salir),
   y la barra inferior ofrece **Descargar**, **Mover**, **Borrar** y, con uno
   solo, **Renombrar**. Desmarcar el último o cambiar de carpeta sale del
   modo.
   - **Mover:** la selección se guarda, se navega hasta la carpeta de destino
     y se pulsa «Mover aquí». Es un renombrado SFTP: no copia nada. Se queda
     donde estaba lo que ya está en esa carpeta, lo que tiene el nombre ocupado
     en el destino y una carpeta que se mueve dentro de sí misma; el mensaje
     dice cuáles.
6. **Cambios que sobreviven a los cortes.** Crear, renombrar, mover y borrar
   se reintentan en el canal nuevo, igual que las transferencias. En cada
   reintento comprueban si el intento anterior llegó a aplicarse.
7. **Cambiar permisos: no.** Descartado por el usuario.

## Alternativas consideradas

- **Subir directamente con el nombre final:** descartado. Un corte dejaría un
  archivo a medias con su nombre real, y reemplazar destruiría el original
  antes de tener el nuevo.
- **Conservar ambos siempre, o reemplazar siempre, sin preguntar:** el usuario
  prefiere que se pregunte cada vez, con la opción de aplicar la respuesta al
  resto de la tanda.
- **Descargar una carpeta como un ZIP:** descartado. El usuario quiere la
  carpeta tal cual. Además, un ZIP no se puede reanudar archivo a archivo.
- **Seguir los enlaces a carpetas al descargar o borrar:** descartado. Al
  descargar pueden formar bucles; al borrar destruirían algo fuera de lo
  elegido.
- **Una sola confirmación también para carpetas con contenido:** descartado
  por el usuario, que pidió el segundo aviso.

## Consecuencias

- Positivas: se puede gestionar el destino sin terminal, también en Windows,
  y las subidas grandes aguantan los microcortes igual que las descargas.
- Negativas / compromisos: `SshSftp` crece con `writeFrom`, `makeDirectory`
  y `removeDirectory`; `rename` y `remove` pasan a dar `SshSftpFailed` cuando
  responde el servidor. `DownloadStore` crece con `createFolder`. Aparece
  `UploadSource` con su selector por plataforma, y `shared` pasa a depender de
  `activity-compose` en Android. Igual que las descargas, una subida vive en
  su pestaña: si se cierra la pestaña o la app, se pierde. Límite conocido:
  si el sistema mata la app o se fuerza su cierre con una subida a medias,
  no hay ocasión de avisar ni de limpiar, y el parcial oculto se queda en el
  destino.
