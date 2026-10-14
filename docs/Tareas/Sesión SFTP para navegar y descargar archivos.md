---
Nombre: 'Sesión SFTP para navegar y descargar archivos'
Estado: 'En curso'
Resumen: 'Un tipo de sesión nuevo, SFTP, junto a terminal y mouse pad: abre un explorador de archivos del destino sobre la conexión SSH de la sesión guardada (mismo host, credenciales y ProxyJump), permite navegar por los directorios y descargar archivos a Descargas del dispositivo. Solo lectura. Las descargas van por bloques con progreso y se reanudan desde donde iban si la conexión se corta.'
Decisiones: 'Decidido con el usuario el 2026-10-14 y recogido en [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]]. Es un tipo de sesión guardada (SessionType.SFTP), no una acción de la terminal; abrir el explorador desde una pestaña ya conectada queda en [[Abrir el explorador SFTP desde una sesión abierta]]. Las descargas van directas a Descargas; elegir la carpeta queda en [[Pantalla de ajustes generales de la app]]. Solo lectura; subir, renombrar, borrar y crear carpetas queda en [[Operaciones de escritura en la sesión SFTP]]. Incluye reanudar la descarga tras un corte.'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-14T12:30:00+02:00
---

# Sesión SFTP para navegar y descargar archivos

## Objetivo

Poder abrir una sesión guardada en modo **SFTP**: en lugar de una terminal,
la pestaña muestra los directorios del destino, se puede navegar por ellos y
descargar archivos al dispositivo. Ver
[[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]].

## Alcance

- Tipo de sesión `SFTP` en el editor de la sesión. La carpeta de trabajo de
  la sesión, si tiene, es la carpeta inicial; si no, la de inicio del
  usuario.
- Explorador: ruta actual, subir, ir al inicio, refrescar; carpetas primero
  y luego archivos por nombre, con tamaño y fecha; los enlaces que apuntan a
  carpetas se abren como carpetas.
- Descarga de un archivo a **Descargas** (Android y escritorio), con
  progreso y cancelar.
- **Reanudar tras un corte**: si la conexión cae durante una descarga, la
  pestaña reconecta y la descarga continúa desde el último byte escrito.
- Destinos Unix y Windows (rutas `/C:/…`).

Fuera de alcance: [[Abrir el explorador SFTP desde una sesión abierta]],
[[Pantalla de ajustes generales de la app]] (elegir carpeta de descargas) y
[[Operaciones de escritura en la sesión SFTP]].

## Criterios de finalización

- Se crea una sesión de tipo SFTP, se abre, se navega y se descarga un
  archivo, en Android y en escritorio.
- Un corte de red en mitad de una descarga grande no la reinicia: continúa
  y el archivo final es idéntico al del destino.
- Funciona contra el contenedor de pruebas y contra un destino Windows.

## Verificación

- `SftpPathsTest` (7) y `SftpBrowserTest` (11): rutas Unix y Windows,
  carpeta inicial, listado, descarga completa, reanudación tras un corte
  desde el último byte, lectura colgada en un enlace muerto, respuesta del
  servidor que falla al momento, error que vuelve sin avanzar, archivo que
  cambia entre medias y cancelar.
- `SshjSftpIntegrationTest` (opt-in) contra el contenedor `titan-test-sshd` y
  contra el sshd de Windows del PC de desarrollo: listado con atributos,
  `stat`, lectura desde un desplazamiento y errores del servidor como
  `SshSftpFailed`.
- Emulador (2026-10-14): sesión SFTP creada desde el editor, se abre en
  `~/proyecto` y lista carpetas y archivos. La primera prueba de corte
  (servidor congelado con `docker pause` a mitad de 300 MB) destapó que la
  descarga fallaba por el tiempo de espera de sshj antes de que el keepalive
  notara la caída; corregido (ver ADR-0021 §5). Repetida con el arreglo:
  congelado al 24 % (73 MB), la descarga pasa a "esperando la conexión", la
  pestaña reconecta al descongelar y sigue desde 73 MB hasta el final; el
  `sha256` del archivo en Descargas coincide con el del servidor. También
  probados: abrir un archivo descargado (selector del sistema), subir de
  carpeta y los ocultos ocultos por defecto.
- Retoques tras la revisión del usuario: la franja pasa a "Reconectando…" a
  la vez que la descarga a "esperando la conexión" (antes tardaba ~25 s más
  mientras se cerraba el canal muerto; ahora se cierra aparte), y a pantalla
  completa el `[v]` de salir va dentro de la franja de estado en vez de tapar
  el `[r]`. Repetido el corte: misma suma `sha256` que el servidor. La caída
  se detecta ~60 s después de congelar el servidor (tiempo de espera de las
  peticiones de sshj).
- Pendiente: probar la ventana de escritorio (desde la sesión 0 no se puede
  ver; solo se ha compilado) y que el usuario lo pruebe en el emulador.
  Android 8–9 (carpeta propia de la app) no se ha probado en dispositivo.

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
