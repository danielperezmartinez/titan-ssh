---
Nombre: 'Canal canary instalable junto a la app estable'
Número: 27
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-20, a petición suya: quiere tener en sus dispositivos una versión canary para probar lo que va a publicar antes de crear el tag. La canary es otra app (otro applicationId, otro paquete de escritorio, otras carpetas de datos y de agente) que se instala junto a la estable. CI la compila en cada push a main y la publica en un único Release rodante con el tag fijo canary, marcado como pre-release. Las versiones ya instaladas lo ignoran porque ese tag no es una versión. El actualizador de la estable deja de aceptar pre-releases y el de la canary solo lee su Release. Hosts y demás configuración se copian de la estable a la canary en un solo sentido. En escritorio la copia incluye los secretos, porque el sistema no los aísla por app. En Android no los incluye, porque el Keystore no deja sacarlos de la app. Sustituye la parte de ADR-0025 que instala las pre-releases en todas las instalaciones.'
Decisión: 'Variante canary de la app (applicationId io.github.danielperezmartinez.titanssh.canary, paquete titan-ssh-canary), construida desde main en cada push y publicada en el Release rodante canary. Tiene sus propias carpetas de datos, su espacio en el almacén de secretos y su carpeta de agente en los destinos. Importa la configuración de la estable en un solo sentido, con secretos solo en escritorio. La estable ignora las pre-releases y el manifiesto firmado indica el canal.'
Consecuencias: 'Cada commit de main llega a los dispositivos del usuario en minutos, sin tocar la instalación estable ni a otros usuarios. El tag vX.Y.Z se pone al commit ya probado en canary. Hay una variante más que construir: release.yml o un workflow nuevo comparte la compilación. La canary nunca escribe en los datos de la estable. En Android las credenciales de la canary se dan de alta aparte. La regla 4 del README cambia: las pruebas en dispositivo pasan a la canary y las pre-releases públicas siguen sin publicarse.'
Reemplaza:
  - '[[ADR-0025 Actualización automática de la app]]'
Reemplazada por: []
Fecha de creación: 2026-10-20T10:40:00+02:00
Última modificación: 2026-10-20T11:05:00+02:00
---

# ADR-0027 · Canal canary instalable junto a la app estable

## Contexto

El 2026-10-20 el usuario pide una versión **canary** instalada en sus
dispositivos (Android, Windows y Linux) para probar lo que va a salir antes de
publicar una versión.

Hoy no se puede:

- [[ADR-0025 Actualización automática de la app]] instala la versión más alta
  de GitHub Releases **con las pre-releases incluidas**, en todas las
  instalaciones. Una beta o un canary publicado como pre-release llegaría a
  todos los usuarios. Por eso la regla 4 del [[README]] prohíbe publicar betas
  hasta que exista un canal opcional.
- Con la misma app no hay forma de tener a la vez la estable y una versión de
  pruebas: comparten `applicationId`, `upgradeUuid` del MSI y carpetas.

Lo que hay en el código y condiciona la decisión:

- `AppVersion.parse` solo acepta `X.Y.Z` y `X.Y.Z-(alpha|beta|rc).N`.
  `UpdateEngine` descarta los Releases cuyo tag no encaja. Un tag `canary`, o
  una versión `-canary.N`, **ya lo ignoran** las versiones publicadas
  (1.0.0–1.1.1), que no se pueden cambiar.
- La configuración es un único `config.json` que se lee entero y se escribe
  entero (`JsonFileConfigStore`), con migraciones solo hacia delante
  (`ConfigMigration`, copia `.v<N>.bak`).
- Escritorio: `config.json`, `known_hosts` y el fichero de agentes en
  `%APPDATA%\titan-ssh` o `~/.config/titan-ssh`. Secretos en el almacén del
  sistema (DPAPI / Secret Service) con el espacio `titan-ssh`
  ([[ADR-0001 Credenciales en almacén nativo del SO]]).
- Android: datos privados de la app. Secretos cifrados con una clave AES del
  `AndroidKeyStore` **no exportable** y ligada al UID de la app.
- Agente de nivel 3 en los destinos: `~/.local/share/titan-ssh` o
  `%LOCALAPPDATA%\titan-ssh`, compartido por cualquier app que se conecte
  ([[ADR-0009 Agente de nivel 3 portable a todos los destinos]]). El ayudante de
  escritorio ([[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]])
  vive en la carpeta local de datos.

## Decisión

### 1. La canary es otra app

- **Android**: `applicationId` `io.github.danielperezmartinez.titanssh.canary`
  y nombre visible *titan-ssh canary*. Se firma con el mismo
  keystore de release: es otra app, así que no choca con la estable.
- **Windows**: MSI por usuario aparte, con su propio `upgradeUuid`, en
  `%LOCALAPPDATA%\Programs\titan-ssh-canary`
  ([[ADR-0026 Carpeta de instalación del MSI aparte de los datos]]), con su
  propio acceso directo.
- **Linux**: `tar.gz` en su propia carpeta y lanzador `titan-ssh-canary`. Sin
  `.deb`, `.rpm`, AUR ni Flatpak: la canary es solo para el usuario.
- **Icono**: el mismo de la estable, con la parte azul de la T en el naranja de
  la app. Lo mismo en Android, Windows y Linux.
- Un único valor de build, el **canal** (`stable` · `canary`), se publica en
  `BuildInfo` y deriva todos los nombres: carpetas de datos y de caché, espacio
  de secretos, carpeta del agente en los destinos y del ayudante de escritorio,
  nombre de la app y URL de actualización. Nada de esto se escribe a mano en
  otro sitio.

### 2. Construcción y publicación

- Un workflow construye la canary **en cada push a `main`** y también a mano
  (`workflow_dispatch`). Reutiliza los mismos jobs de compilación y tests que
  `release.yml`.
- Se publica en **un único Release rodante** con el tag fijo `canary`, marcado
  como *pre-release* y nunca como *latest*. En cada build se mueve el tag al
  commit nuevo y se sustituyen sus assets. Así la lista de Releases no se llena
  de versiones de prueba.
- **Versión**: la siguiente versión de parche al último tag estable, más
  `-canary.<run>`, donde `<run>` es el número de ejecución del workflow (por
  ejemplo `1.1.2-canary.57`). El `versionCode` de Android y la versión del MSI
  de la canary salen de `<run>`, que es estrictamente creciente. Al ser otra app
  no compiten con la fórmula de la estable de
  [[Versionado único desde tag de git]].
- Lleva su `titan-update.json` firmado con la misma clave Ed25519
  (`TITAN_UPDATE_SIGNING_KEY`). El manifiesto añade el campo firmado
  **`channel`**.

### 3. Actualizador

- **Estable**: solo considera Releases que no sean pre-release, con versión
  `X.Y.Z` y manifiesto con `channel` ausente o `stable`. Deja de instalar
  pre-releases. Esto **sustituye** la parte de ADR-0025 que las incluía.
- **Canary**: solo mira el Release `canary`. Acepta el manifiesto si está
  firmado, tiene `channel: canary` y su `<run>` es mayor que el instalado.
  Nunca instala una versión estable encima de sí misma, porque es otra app.
- Las versiones estables ya publicadas ignoran el Release `canary`, porque su
  tag no es una versión válida para `AppVersion.parse`. Se comprueba con un
  test.

### 4. Hosts y configuración: copia en un solo sentido

El usuario quiere compartir hosts y demás si no supone un problema de
seguridad. Se comparte **copiando de la estable a la canary**, nunca
escribiendo las dos en los mismos ficheros:

- **Al primer arranque** de la canary, y cuando se pulsa *Copiar de la app
  estable* en Ajustes, la canary sustituye su configuración por la de la
  estable: `config.json` (con su migración si la canary va por delante) y
  `known_hosts`.
- **Escritorio**: copia también los **secretos** al espacio
  `titan-ssh-canary` del almacén. No baja la seguridad. DPAPI y Secret Service
  protegen por usuario del sistema y no por app: cualquier proceso del usuario,
  incluida la canary, ya podía leer el espacio `titan-ssh`.
- **Android**: **sin secretos**. La clave del Keystore de la estable no sale de
  su UID. Pasar los secretos en claro a otra app abriría justo el hueco que
  ADR-0001 cierra. La configuración sin secretos y `known_hosts` llegan a la
  canary mediante un `ContentProvider` de **solo lectura** de la estable,
  protegido por un permiso `signature` (solo una app firmada con el mismo
  keystore puede leerlo). Los hosts copiados quedan *pendientes de credencial*,
  igual que en [[Exportar e importar la configuración sin secretos]]. Contraseñas
  y claves se dan de alta en la canary una vez.
- La canary **nunca escribe** en los datos ni en el espacio de secretos de la
  estable.

### 5. Agente y ayudante de escritorio separados

- En los destinos, la canary instala su agente en `titan-ssh-canary` (en lugar
  de `titan-ssh`). Sus sesiones de nivel 3 y sus actualizaciones del agente no
  tocan nunca las de la estable.
- Lo mismo con el ayudante de escritorio y el estado local del agente.

## Alternativas consideradas

- **Rama `develop` y PR a `main`**: descartada. En este repositorio `main` no
  publica nada; solo publica el tag. `main` ya hace de rama de desarrollo y una
  rama más solo añade merges.
- **Canal beta en Ajustes, la misma app con pre-releases `-rc.N`**: descartada.
  Sustituye a la estable en vez de convivir con ella, obliga a crear un tag
  público por cada prueba y llena la lista de Releases.
- **Compartir los mismos ficheros y el mismo espacio de secretos**: descartada.
  En escritorio no es un problema de seguridad, pero sí de integridad. Las dos
  apps escriben `config.json` entero, así que gana la última escritura. Además
  una canary con un esquema más nuevo migraría el fichero y la estable perdería
  los campos que no conoce al guardar. En Android tampoco se puede: los datos y
  el Keystore son por app.
- **`sharedUserId` en Android**: descartada. Está obsoleto y no se puede añadir
  a una app ya instalada sin desinstalarla.
- **Sincronización en los dos sentidos**: descartada por ahora. Exige resolver
  conflictos entre dos configuraciones vivas. Con la copia en un solo sentido
  basta para probar.
- **Artefactos del run de Actions en vez de un Release**: descartada. Caducan,
  exigen iniciar sesión y el actualizador no puede leerlos sin autenticación.

## Consecuencias

- Positivas: cada commit de `main` llega a los dispositivos del usuario sin
  afectar a nadie más ni a su instalación estable. El tag `vX.Y.Z` se pone al
  commit que ya se probó en canary. La regla de no publicar betas queda
  cerrada por diseño: la estable ya no instala pre-releases.
- Negativas / compromisos:
  - Una variante más que construir y mantener.
  - Si `<run>` se reinicia (workflow renombrado o recreado), la canary deja de
    actualizarse hasta reinstalarla.
  - Un `ContentProvider` nuevo, exportado, en la app estable de Android,
    protegido por firma.
  - Las credenciales de la canary en Android se dan de alta aparte.
  - La configuración de la canary se pierde al volver a copiarla desde la
    estable.
  - El Release `canary` es público, como todo el repositorio: cualquiera puede
    descargar la canary.
- ADR-0025 pasa a `Reemplazada` solo en la política de pre-releases. La regla 4
  del [[README]] se actualiza cuando la estable deje de instalar pre-releases,
  con la implementación.
- El 2026-10-20 el usuario confirma que no hace falta pasar contraseñas ni
  claves software a la canary de Android: las credenciales que le interesaban
  son las claves hardware, que no se pueden pasar.
- Implementación en [[Canal canary instalable junto a la app estable]].
