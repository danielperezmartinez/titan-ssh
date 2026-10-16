---
Nombre: 'Carpeta de instalación del MSI aparte del estado del agente'
Estado: 'Hecha'
Resumen: 'Al instalar a mano el MSI de la beta.20 encima de la beta.6, el asistente avisó de que la carpeta ya existía y después se paró con «Another application has exclusive access to agent.lock». Causa: el MSI instala la app en %LOCALAPPDATA%\titan-ssh, la misma carpeta donde viven el estado del agente de nivel 3 y la caché de la app, y jpackage añade a cada MSI un RemoveFolderEx que, al desinstalar, borra INSTALLDIR entero. Cada actualización desinstala la versión vieja, así que vacía esa carpeta y choca con los ficheros que el agente tiene abiertos; con msiexec /qn (ADR-0025) la actualización fallaría sola. Se instala la app en %LOCALAPPDATA%\Programs\titan-ssh y sin selector de carpeta, con un MSI que genera la tarea propia jpackageMsi (WiX exige registrar la carpeta Programs y el packageMsi de Compose no deja añadirlo). Hecha el 2026-10-16: probada con un producto de prueba, incluida una actualización con el script real del actualizador y agent.lock abierto, que ya no toca los datos.'
Decisiones: 'Ver [[ADR-0026 Carpeta de instalación del MSI aparte de los datos]]. Opción (a) elegida por el usuario el 2026-10-16: el paso desde una beta (≤ beta.20) a la 1.0.0 vacía una última vez %LOCALAPPDATA%\titan-ssh, porque esa desinstalación usa el MSI viejo; se acepta y basta con no tener sesiones de nivel 3 contra ese PC al instalar. Descartada (b), retocar el MSI para neutralizar el RemoveFolderEx de la versión vieja: solo serviría una vez y para una instalación. El usuario prefirió Programs\titan-ssh a una carpeta de un solo nivel (titan-ssh-app), que no habría necesitado la tarea propia.'
Bloqueada: []
Versión: '1.0.0'
Fecha de creación: 2026-10-16T18:50:00+02:00
Última modificación: 2026-10-16T19:25:00+02:00
---

# Carpeta de instalación del MSI aparte del estado del agente

## Objetivo

Que instalar o actualizar la app en Windows no toque nunca los ficheros del
agente de nivel 3 ni las cachés de la app, y que el asistente no avise de que
la carpeta de instalación ya existe.

Lo que vio el usuario el 2026-10-16, instalando a mano el MSI de la
`0.1.0-beta.20` encima de su `0.1.0-beta.6` en un PC que es a la vez cliente y
destino Windows:

1. El asistente avisó de que la carpeta ya existía y preguntó si instalar
   igualmente.
2. A mitad de instalación: «Another application has exclusive access to the
   file '…\AppData\Local\titan-ssh\agent.lock'. Please shut down all other
   applications, then click Retry.» (error 1306 de Windows Installer).

El usuario canceló: la desinstalación de la beta.6 se deshizo y la beta.6 quedó
como estaba.

## Diagnóstico

Comprobado leyendo las tablas de los dos MSI (el de la beta.6 que guarda
Windows y el de la beta.20):

- `perUserInstall = true` instala en `%LOCALAPPDATA%\<packageName>`, es decir
  `%LOCALAPPDATA%\titan-ssh`. Es la misma carpeta que usan:
  - el agente de nivel 3 en Windows como directorio de estado (`agent.json`,
    `agent.lock`, `session-*.json/.lock`, el binario versionado; ver
    [[titan-agent instancia única y directorio de estado]] e
    [[Instalación del agente en destinos Windows y multi-SO]]);
  - el ayudante de escritorio (`desktop.json`, `desktop.lock`,
    `desktop-<versión>.exe`; [[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]);
  - la caché de la app (`cache\agent` y `cache\update`, donde el actualizador de
    [[ADR-0025 Actualización automática de la app]] deja el MSI, el script y
    `msiexec.log`).
- jpackage añade a cada MSI un `util:RemoveFolderEx On="uninstall"` sobre
  `INSTALLDIR` (tablas `WixRemoveFolderEx`, `AppSearch` y `RegLocator`; la ruta
  se guarda en `HKCU\Software\<vendor>\titan-ssh\<versión MSI>`). Al desinstalar
  borra la carpeta entera, con todo lo que contenga.
- `RemoveExistingProducts` va antes de `CostInitialize`: cada actualización
  desinstala primero la versión vieja, con su `RemoveFolderEx`. Con el agente o
  el desktop-run en marcha, sus ficheros abiertos dan el 1306. Con `msiexec /qn`
  no hay a quién preguntar y la instalación falla.
- La prueba del actualizador en Windows no lo vio: el MSI de prueba tenía otro
  nombre, así que su carpeta no contenía `titan-ssh\cache` ni el estado del
  agente.
- El aviso de carpeta existente es el diálogo `InstallDirNotEmptyDlg` de
  jpackage (`JpCheckInstallDir`), que solo existe con `dirChooser = true`.

## Criterios de finalización

- El MSI instala la app en `%LOCALAPPDATA%\Programs\titan-ssh` y no ofrece
  elegir carpeta.
- Una actualización entre dos MSI nuevos, con `msiexec /qn` como la lanza el
  actualizador, termina bien con un fichero bloqueado en el directorio de estado
  del agente, y no borra nada de esa carpeta.
- El paso desde un MSI con la disposición vieja funciona y deja la app en la
  carpeta nueva.
- La desinstalación borra la carpeta de la app y los accesos directos.
- La app instalada arranca y el actualizador detecta la instalación MSI.
- Documentado en la ADR y en las notas de la 1.0.0 (instalar sin sesiones de
  nivel 3 contra ese PC).

## Verificación

Hecho el 2026-10-16 en la rama `claude/msi-install-dir-bfddab`, sin tocar la
instalación real: un producto de prueba `titan-msitest` con otro
`upgradeUuid` (cambios temporales en `desktopApp/build.gradle.kts`, no
subidos), versiones `0.9.1-beta.1` (disposición vieja, construida con el
`packageMsi` de Compose), `0.9.1-beta.2` y `0.9.1-beta.3` (disposición nueva).
Su carpeta de datos hace de `%LOCALAPPDATA%\titan-ssh`: `agent.json`,
`agent.lock`, `session-*.json`, `cache\agent` y `cache\update`, con
`agent.lock` abierto por otro proceso como lo abre el agente en Go (compartido
para leer y escribir, sin borrado).

- **Build**: el MSI nuevo tiene `INSTALLDIR` en
  `LocalAppDataFolder\Programs\titan-msitest`, la fila de `RemoveFile` para
  `Programs` y ningún evento hacia `InstallDirDlg` ni `JpCheckInstallDir`. Las
  mismas tablas, accesos directos, icono y número de ficheros (276) que el MSI
  de Compose. Con la identidad real sale `titan-ssh-0.9.134.msi` con
  `INSTALLDIR` en `Programs\titan-ssh` y el mismo `UpgradeCode`.
- **Disposición vieja → nueva** (el paso de una beta a la 1.0.0), con
  `msiexec /qn` y `agent.lock` abierto: termina con 0, la app queda en
  `Programs\titan-msitest` y la carpeta vieja se vacía entera, como se
  esperaba. Además, el Restart Manager **cerró el proceso que tenía
  `agent.lock` abierto** («Successfully shut down all applications … that held
  files in use»). Con el agente real eso cerraría el agente y sus sesiones.
- **Nueva → nueva con el script real del actualizador**
  (`install-windows.ps1` desde `cache\update\0.9.1-beta.3\`, como lo lanza la
  app) y `agent.lock` abierto: resultado 0, queda instalada la 0.9.133 en
  `Programs\titan-msitest`, el proceso del candado sigue vivo, y
  `agent.json`, `session-*.json`, `cache\agent` y el MSI de `cache\update`
  siguen ahí. Lanzado desde pwsh 7, el script fallaba con código 1 por el
  `PSModulePath` que pwsh 7 hereda a Windows PowerShell 5.1. La app lo lanza
  desde Java y no le pasa, y con el `PSModulePath` del sistema funciona.
- **La app instalada** arranca y abre su ventana.
- **Desinstalar** (`msiexec /x /qn`): 0. Se borran la carpeta de la app, el
  acceso directo del escritorio, el del menú Inicio y la clave de registro, y
  `%LOCALAPPDATA%\Programs` se queda porque hay otras apps. La carpeta de datos
  queda intacta.
- **Linux**: Gradle configura y planifica `packageDeb` y `packageTarGz` en el
  contenedor `titan-linux-pkg` con el cambio.
- Limpieza: producto de prueba desinstalado y su carpeta de datos borrada.

**La instalación real del usuario** (beta.6), tras cancelar el 2026-10-16: sus
ficheros volvieron y el producto sigue registrado, pero su `UpgradeCode` quedó
sin producto asociado (la clave de `UpgradeCodes` está vacía), así que el MSI
de la 1.0.0 no la reconocerá como versión anterior y se instalaría al lado.
Para pasar a la 1.0.0: desinstalar antes la beta.6 desde Configuración →
Aplicaciones, sin sesiones de nivel 3 contra ese PC, y después instalar la
1.0.0.

## Resultado

- `desktopApp/build.gradle.kts`: `installationPath = "Programs/titan-ssh"` y
  `dirChooser = false`; tareas `generateMsiWixTemplate` y `jpackageMsi`, y
  `packageMsi` delega en esta (ver
  [[ADR-0026 Carpeta de instalación del MSI aparte de los datos]]). CI sigue
  llamando a `:desktopApp:packageMsi` sin cambios.
- Pendiente con la release 1.0.0: decir en sus notas que quien tenga una beta
  la instale sin sesiones de nivel 3 contra ese PC.
