---
Nombre: 'Sesión SFTP de solo lectura con descargas reanudables'
Número: 21
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-14. Se añade un tercer tipo de sesión guardada, SFTP, junto a terminal y mouse pad: la pestaña muestra un explorador de archivos del destino sobre la misma conexión SSH (host, credenciales, ProxyJump y verificación de host de siempre), sin agente. La primera versión es de solo lectura: navegar y descargar. Las descargas van directas a la carpeta Descargas del dispositivo, por bloques con progreso, y sobreviven a los cortes: la pestaña reconecta como cualquier otra y la descarga sigue desde el último byte escrito.'
Decisión: 'Las sesiones guardadas pueden ser de tipo SFTP: un explorador de solo lectura del destino que descarga a Descargas y reanuda las descargas tras un corte, usando el ciclo de conexión y reconexión de las pestañas.'
Consecuencias: 'El usuario obtiene archivos del destino sin salir de la app y sin depender del agente, también en destinos Windows. SshSftp crece con un listado con atributos y una lectura por bloques desde un desplazamiento. La carpeta de descargas es fija hasta [[Pantalla de ajustes generales de la app]]; abrir el explorador desde una terminal ya conectada queda para [[Abrir el explorador SFTP desde una sesión abierta]] y escribir en el destino para [[Operaciones de escritura en la sesión SFTP]].'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-14T11:10:00+02:00
Última modificación: 2026-10-14T12:30:00+02:00
---

# ADR-0021 · Sesión SFTP de solo lectura con descargas reanudables

## Contexto

El usuario quiere poder navegar por los directorios del destino y descargar
archivos desde la app ([[Sesión SFTP para navegar y descargar archivos]]).

Ya había piezas que lo hacen posible:

- `SshSftp` (en `SshSession.kt`) y su implementación sshj, creados para que
  el instalador del agente suba el binario
  ([[ADR-0009 Agente de nivel 3 portable a todos los destinos]] §7). Resuelven
  la carpeta de inicio (`canonicalize(".")`) y las rutas de Windows
  (`/C:/Users/u`). Pero su `list` solo da nombres y `read` carga el archivo
  entero en memoria: no sirven para un explorador ni para descargas grandes.
- El tipo de sesión de [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]:
  una pestaña con su propia vista que reutiliza el ciclo de conexión de
  `SessionTab` (verificación de host, ProxyJump, reconexión).

## Decisión

Decidido con el usuario el 2026-10-14:

1. **Tipo de sesión.** `SessionType.SFTP`, tercer valor junto a `TERMINAL` y
   `MOUSEPAD`. Los campos de terminal (scripts, túneles, resiliencia,
   apariencia, reapertura) no se aplican y se conservan, como en el mouse pad.
   El **directorio inicial** de la sesión es la carpeta en la que se abre el
   explorador; si no tiene o no existe, la de inicio del usuario. No usa el
   agente: basta con que el servidor ofrezca el subsistema `sftp`.
2. **Una pestaña, un canal SFTP.** La pestaña conecta como cualquier otra y
   abre un canal SFTP. Si el servidor no lo ofrece, la pestaña falla con ese
   motivo. Una sesión SFTP no deja nada en el destino: abrirla de nuevo abre
   otra pestaña independiente (no hay instancias que reenganchar, ADR-0020).
3. **Solo lectura.** Navegar (entrar, subir, ir al inicio, escribir una ruta,
   refrescar) y descargar archivos. Carpetas primero y luego archivos, por
   nombre; los enlaces se resuelven para saber si llevan a una carpeta. Subir,
   renombrar, borrar o crear carpetas queda en
   [[Operaciones de escritura en la sesión SFTP]].
4. **Destino de las descargas: Descargas**, sin preguntar. Si ya existe un
   archivo con ese nombre se usa `nombre (1).ext`, `(2)`… En Android 10+ va a
   la colección `Downloads` de MediaStore (sin permisos) y queda marcado como
   pendiente hasta terminar; en Android 8–9, que no la tienen, a la carpeta
   de descargas propia de la app. En escritorio, a la carpeta `Downloads` del
   usuario. Elegir otra carpeta será un ajuste de
   [[Pantalla de ajustes generales de la app]].
5. **Descargas reanudables.** El archivo se lee por bloques (lectura
   anticipada de sshj con varias peticiones en vuelo) y se escribe tal como
   llega, con progreso y cancelar. Si la conexión cae, la pestaña reconecta con
   su política de siempre ([[Resiliencia de sesión ante microcortes de red]]) y
   cada descarga pendiente continúa en el canal nuevo **desde el último byte
   escrito**, sin volver a empezar. Si el archivo del destino cambió de tamaño
   entre medias, la descarga falla en vez de mezclar dos versiones. Cancelar o
   cerrar la pestaña borra el archivo a medias.
   Una petición que falla sin ser la respuesta del servidor (típicamente el
   tiempo de espera de sshj, 30 s, sobre un enlace muerto) se toma como
   conexión caída y la pestaña reconecta al momento: el keepalive tardaría
   bastante más en notarlo (visto en la prueba con el servidor congelado). Si
   el mismo error vuelve en 5 conexiones seguidas sin avanzar un byte, la
   descarga falla con ese error.
6. **Abrir el explorador desde una terminal abierta** no forma parte de esta
   decisión: queda en [[Abrir el explorador SFTP desde una sesión abierta]],
   que el usuario quiere colgar del menú de la pestaña de
   [[Renombrar las pestañas y las sesiones activas]].

## Alternativas consideradas

- **Acción dentro de una pestaña de terminal en lugar de un tipo de sesión**:
  aplazada a su propia tarea. El tipo de sesión reutiliza el modelo que ya
  existe y no necesita el menú de pestaña, que aún no existe.
- **Preguntar el destino de cada descarga** (selector del sistema): aplazado.
  El usuario prefiere por ahora ir directo a Descargas y configurar la carpeta
  en una pantalla de ajustes.
- **Leer el archivo entero y guardarlo al final** (el `read` existente):
  descartado. No muestra progreso, gasta memoria y, si la conexión se corta,
  obliga a empezar de cero.
- **Reanudar con un nuevo canal en cada intento sin reconectar la pestaña**:
  descartado. Un corte tira toda la conexión SSH, así que lo correcto es
  apoyarse en la reconexión de la pestaña y retomar en el canal nuevo.

## Consecuencias

- Positivas: navegar y descargar sin salir de la app ni depender del agente,
  también en destinos Windows; las descargas grandes aguantan los microcortes,
  el problema que dio origen al proyecto.
- Negativas / compromisos: `SshSftp` crece (listado con atributos, `stat` y
  lectura desde un desplazamiento). La descarga pendiente vive en la pestaña:
  si se cierra la pestaña o la app, se pierde. Android 8–9 descarga a una
  carpeta de la app, menos visible que Descargas.
