---
Nombre: 'Carpeta de instalación del MSI aparte de los datos'
Número: 26
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-16. El MSI por usuario instalaba la app en %LOCALAPPDATA%\titan-ssh, la carpeta donde también viven el estado del agente de nivel 3, el ayudante de escritorio y la caché de la app (incluido el actualizador). jpackage añade a cada MSI un RemoveFolderEx que, al desinstalar, borra entera la carpeta de instalación, y cada actualización desinstala la versión vieja: vaciaba esa carpeta y chocaba con los ficheros abiertos del agente (error 1306, o fallo silencioso con msiexec /qn). Desde la 1.0.0 la app se instala en %LOCALAPPDATA%\Programs\titan-ssh, sin selector de carpeta, y %LOCALAPPDATA%\titan-ssh queda solo para datos. El paso desde una beta vacía esa carpeta una última vez.'
Decisión: 'En Windows la app se instala en %LOCALAPPDATA%\Programs\titan-ssh (installationPath) sin elegir carpeta (dirChooser = false). El MSI lo genera la tarea propia jpackageMsi, que añade a la plantilla WiX de jpackage el borrado de la carpeta Programs (WiX lo exige). En la carpeta de instalación solo hay ficheros del MSI; el estado del agente, el ayudante de escritorio y las cachés siguen en %LOCALAPPDATA%\titan-ssh.'
Consecuencias: 'Actualizar o desinstalar la app ya no toca los datos del agente ni la caché del actualizador. No cambia nada en el agente ni en los destinos Windows. El paso desde una beta (≤ 0.1.0-beta.20) usa la desinstalación del MSI viejo, que vacía %LOCALAPPDATA%\titan-ssh una última vez: se instala sin sesiones de nivel 3 contra ese PC. Nada nuevo puede guardarse dentro de la carpeta de instalación.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-16T18:55:00+02:00
Última modificación: 2026-10-16T19:25:00+02:00
---

# ADR-0026 · Carpeta de instalación del MSI aparte de los datos

## Contexto

[[Configuración de release del escritorio]] eligió un MSI por usuario
(`perUserInstall = true`): sin administrador ni UAC. jpackage lo instala en
`%LOCALAPPDATA%\<packageName>`, o sea `%LOCALAPPDATA%\titan-ssh`.

Esa carpeta es también la de los datos de titan-ssh en Windows:

- el directorio de estado del agente de nivel 3
  ([[ADR-0009 Agente de nivel 3 portable a todos los destinos]]): `agent.json`,
  `agent.lock`, `session-*.json/.lock` y el binario versionado;
- el ayudante de escritorio
  ([[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]):
  `desktop.json`, `desktop.lock`, `desktop-<versión>.exe`;
- la caché de la app: `cache\agent` y `cache\update`, donde el actualizador de
  [[ADR-0025 Actualización automática de la app]] deja el MSI, el script y el
  log de `msiexec`.

jpackage añade a cada MSI un `util:RemoveFolderEx On="uninstall"` sobre la
carpeta de instalación, y su `RemoveExistingProducts` va antes de
`CostInitialize`: **cada actualización desinstala la versión vieja y, con ella,
borra la carpeta entera**. Si el agente o el desktop-run están en marcha, sus
ficheros abiertos paran la instalación con el error 1306 («Another application
has exclusive access…»); con `msiexec /qn` la instalación falla sin avisar. Lo
vio el usuario el 2026-10-16 al instalar a mano la beta.20, junto al aviso de
que la carpeta ya existía (`InstallDirNotEmptyDlg`, que jpackage solo incluye
con `dirChooser = true`). Detalle en
[[Carpeta de instalación del MSI aparte del estado del agente]].

## Decisión

- La app se instala en **`%LOCALAPPDATA%\Programs\titan-ssh`**
  (`installationPath = "Programs\\titan-ssh"`), la carpeta habitual de las apps
  por usuario en Windows. Sigue sin administrador ni UAC.
- **Sin selector de carpeta** (`dirChooser = false`). El actualizador instala
  siempre encima de la misma carpeta, y desaparece el aviso de carpeta
  existente.
- **`%LOCALAPPDATA%\titan-ssh` queda solo para datos**: no se mueve nada. El
  agente, el ayudante de escritorio y las cachés siguen donde estaban, así que
  no cambia nada en los destinos Windows ni en los agentes ya desplegados.
- **Regla**: nada que no instale el MSI se guarda dentro de la carpeta de
  instalación, porque la desinstalación lo borraría.
- **Cómo se genera el MSI**: jpackage no registra la carpeta intermedia
  `Programs` para borrarla al desinstalar, y WiX rechaza el MSI (ICE64, «is in
  the user profile but is not listed in the RemoveFile table»). Su único
  gancho es un `main.wxs` propio en el `--resource-dir`, y el `packageMsi` de
  Compose vacía ese directorio justo antes de llamar a jpackage. Por eso
  `desktopApp/build.gradle.kts` genera el MSI con su propia tarea
  `jpackageMsi`: `jpackage --type msi --app-image` sobre la imagen de
  `createDistributable`, con las opciones del DSL de Compose y un `main.wxs`
  que es la plantilla del JDK que empaqueta (leída de su módulo
  `jdk.jpackage`, no copiada al repositorio) más un componente que borra
  `Programs` al desinstalar si queda vacía. `packageMsi` sigue siendo la tarea
  que se llama y delega en `jpackageMsi`. Si jpackage cambia la forma de
  nombrar las carpetas o su plantilla, el build falla en vez de generar un MSI
  distinto.
- **Paso desde una beta** (≤ `0.1.0-beta.20`): esa desinstalación la hace el
  MSI viejo, que no se puede cambiar, así que vacía `%LOCALAPPDATA%\titan-ssh`
  una última vez. Se acepta: todo lo que hay ahí se regenera, y basta con
  instalar sin sesiones de nivel 3 contra ese PC (si no, sale el mismo 1306).
  Las notas de la 1.0.0 lo dicen.

## Alternativas consideradas

- **Mover los datos y dejar la app donde está**: descartado. Obliga a migrar el
  estado del agente en todos los destinos Windows y a cambiar el agente, y la
  regla de jpackage seguiría borrando lo que la app dejara junto a sí.
- **Una carpeta de un solo nivel (`%LOCALAPPDATA%\titan-ssh-app`)**: funciona
  con jpackage tal cual, sin `main.wxs` propio. El usuario prefirió el 2026-10-16
  la carpeta convencional `Programs\titan-ssh`, aceptando la tarea de build
  propia.
- **Quitar el `RemoveFolderEx` retocando el MSI generado**: descartado. Es un
  paso más en CI, frágil ante cambios de jpackage, y la desinstalación limpia de
  la carpeta de la app es útil.
- **Neutralizar el `RemoveFolderEx` de la versión vieja durante el paso a la
  1.0.0** (borrar antes el valor de registro del que saca la ruta): descartado
  por el usuario el 2026-10-16. Solo serviría una vez y para una instalación.
- **Dejarlo como está**: descartado. Cada actualización automática fallaría con
  el agente en marcha en ese PC, y vaciaría la caché del propio actualizador.

## Consecuencias

- Positivas: actualizar y desinstalar ya no tocan los datos; el actualizador
  automático no choca con el agente; el asistente no pregunta por la carpeta.
- Negativas / compromisos:
  - El paso desde una beta mueve la app de carpeta y borra una vez los datos
    del agente en ese PC. Con `-Relaunch`, el actualizador de la beta.20
    intentaría reabrir la ruta vieja y no reabriría la app.
  - La carpeta de instalación queda fijada: cambiarla otra vez tendría el mismo
    coste de transición.
  - El MSI ya no lo arma el `packageMsi` de Compose sino una tarea propia
    (`jpackageMsi`), que hay que revisar si se cambia el DSL de
    `nativeDistributions.windows` o se actualiza Compose o el JDK.
  - Con `msiexec /qn`, el Restart Manager de Windows **cierra** los procesos que
    tienen abiertos ficheros que el MSI va a borrar, en vez de preguntar. Con la
    disposición vieja eso habría cerrado el agente y todas las sesiones de nivel
    3 de ese PC en una actualización automática. Con la nueva no hay nada del
    agente dentro de la carpeta de instalación.
