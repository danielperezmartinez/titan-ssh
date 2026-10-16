---
Nombre: 'Actualización automática de la app'
Número: 25
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-16, con .deb y .rpm pidiendo la contraseña. Petición del usuario del 2026-10-16: no esperar a winget ni a AUR y que la app se actualice sola, como Claude Code Desktop. En Windows, Linux y Android la app descarga en segundo plano la versión más nueva, betas incluidas, y la instala sola por defecto. En Ajustes se puede cambiar a preguntar antes de instalar. Nunca cierra pestañas abiertas: instala al cerrar o al arrancar la app. Las actualizaciones van firmadas con una clave Ed25519 propia y gratuita, cuya clave pública va dentro de la app. Así no hace falta la firma de SignPath. Se apaga en los canales cuyo gestor es el dueño de los ficheros: Flatpak, AUR y Google Play. Sustituye la parte de ADR-0011 que decía avisar sin descargar ni ejecutar nada.'
Decisión: 'La app se actualiza sola en las tres plataformas a partir de un manifiesto firmado con una clave Ed25519 propia y publicado en cada GitHub Release. Por defecto instala sin preguntar y en Ajustes se cambia a preguntar. Incluye las betas. No interrumpe pestañas abiertas y no se usa en Flatpak, AUR ni Play.'
Consecuencias: 'La clave privada de actualizaciones pasa a ser un secreto crítico de CI, como el keystore: quien la tenga puede hacer que todas las instalaciones ejecuten su código. release.yml firma un manifiesto nuevo. La app necesita un actualizador por plataforma (msiexec por usuario en Windows, sustituir el tar.gz en Linux, pkexec con contraseña para .deb y .rpm, PackageInstaller en Android con el permiso de instalar apps). La primera actualización en Android siempre pide confirmación. ADR-0011 queda sustituida en su política de actualización.'
Reemplaza:
  - '[[ADR-0011 Distribución y canales de publicación]]'
Reemplazada por: []
Fecha de creación: 2026-10-16T09:30:00+02:00
Última modificación: 2026-10-16T12:20:00+02:00
---

# ADR-0025 · Actualización automática de la app

## Contexto

[[ADR-0011 Distribución y canales de publicación]] deja las actualizaciones en
manos de cada canal: winget, AUR, Flathub, Obtainium e IzzyOnDroid. Para los
paquetes sueltos (MSI, `.deb`, `.rpm`, `tar.gz`) solo prevé un aviso en la app
que **nunca descarga ni ejecuta nada**
([[Actualización automática de la app|la tarea del paso 14]] en su forma
anterior). También descartó Conveyor porque los canales ya actualizaban.

Esos canales no llegan:

- AUR tiene cerrado el registro de cuentas nuevas desde el 2026-09-26.
- La primera PR de winget lleva semanas sin fusionarse.
- Flathub e IzzyOnDroid son de la fase 5.
- El usuario no usa Obtainium en Android.

Así que hoy cada versión se instala a mano en todas las plataformas.

El 2026-10-16 el usuario pide que la app se actualice sola, como Claude Code
Desktop, en las **tres plataformas**, **betas incluidas**, **instalando sin
preguntar por defecto** y con una opción en Ajustes para que pregunte antes.

Restricciones:

- **Coste cero** (regla 4 del [[README]]): nada de certificados de pago.
- **Sin firma de Windows**: SignPath depende de que la concedan
  ([[Firma de código Windows con SignPath Foundation]]). Además, SmartScreen
  solo evalúa los ficheros con la marca de "descargado de Internet", que pone
  el navegador y no una descarga hecha por la propia app.
- **Seguridad, prioridad alta**: un actualizador que descarga y ejecuta código
  es la superficie más delicada de la app. HTTPS y un `SHA256SUMS` del mismo
  Release no bastan: quien controle la cuenta de GitHub o el Release controla
  las dos cosas a la vez.
- **Sesiones vivas**: actualizar reinicia la app. Las pestañas de nivel 1 y 2
  perderían su conexión. Las de nivel 3 se recuperan gracias al agente.

## Decisión

### 1. Origen y manifiesto firmado

- Cada Release publica un asset nuevo, `titan-update.json`, con la versión, la
  fecha y, por artefacto (MSI, `.deb`, `.rpm`, `tar.gz` por arquitectura y
  APK), su nombre y su SHA-256. Lo acompaña `titan-update.json.sig`, una firma
  **Ed25519** del manifiesto.
- La clave privada vive solo en GitHub Secrets (`TITAN_UPDATE_SIGNING_KEY`) y
  la custodia el usuario, igual que el keystore. La clave pública se compila
  en la app. Se prevé una lista de claves aceptadas para poder rotarla con una
  versión intermedia.
- La app consulta `GET /repos/danielperezmartinez/titan-ssh/releases`, sin
  autenticación ni datos del usuario, al arrancar y después como mucho cada
  6 horas. Toma la versión más alta que no sea borrador, **incluidas las
  pre-releases**, y descarga su manifiesto.
- Antes de instalar, la app comprueba tres cosas:
  1. la firma del manifiesto con la clave pública;
  2. que la versión es **estrictamente mayor** que la instalada, según el
     orden SemVer de [[Versionado único desde tag de git]], lo que impide
     volver a una versión anterior;
  3. el SHA-256 del artefacto descargado.

  Si algo falla, no instala y lo registra, sin diálogos de error.

### 2. Política de instalación

- **Ajuste nuevo** en Configuración → Ajustes (`AppSettings`): *Actualizaciones*
  = `Instalar automáticamente` (por defecto) · `Preguntar antes de instalar`.
  También hay un botón **Buscar ahora** y la versión pendiente, si la hay, en
  Acerca de.
- La descarga se hace siempre en segundo plano. Lo que cambia con el ajuste es
  el paso de instalar:
  - **Automático**: se instala en cuanto no hay **ninguna pestaña abierta**: al
    cerrar la app o al arrancarla, antes de abrir nada. No se cierra nunca una
    pestaña por actualizar. Si hay pestañas, se espera.
  - **Preguntar**: un aviso discreto ofrece *Instalar y reiniciar* o *Más
    tarde*. Con pestañas abiertas, avisa de lo que se perdería.
- Tras actualizar, la app se reabre y muestra la versión nueva en Acerca de.

### 3. Mecanismo por plataforma

| Instalación | Cómo se actualiza |
|---|---|
| Windows, MSI por usuario (`perUserInstall`) | Al salir, un proceso auxiliar espera a que se cierre la app y lanza `msiexec /i <msi> /qn` sobre el mismo `upgradeUuid`. Después reabre la app. No pide permisos de administrador. |
| Windows, instalada con winget | Igual que el MSI suelto. winget lee la versión instalada y deja de verla como desfasada, así que no se desactiva. |
| Linux, `tar.gz` | Descomprime la versión nueva junto a la actual y cambia la carpeta al reiniciar. Solo si la carpeta es del usuario; si no, solo avisa. |
| Linux, `.deb` / `.rpm` | Descarga el paquete e instala con `pkexec apt install` o `pkexec dnf install`. El sistema **siempre pide la contraseña**: es inevitable sin un servicio con root. |
| Android, APK de Releases | `PackageInstaller` con el permiso *Instalar apps desconocidas* (`REQUEST_INSTALL_PACKAGES`). El sistema exige que la APK nueva esté firmada con la misma clave. La **primera** actualización siempre pide confirmación. En Android 12 o superior, cuando la app ya es quien instaló la versión actual, las siguientes van sin confirmación (`USER_ACTION_NOT_REQUIRED` y el permiso `UPDATE_PACKAGES_WITHOUT_USER_ACTION`). Comprobado en el emulador el 2026-10-16. La descarga espera en `noBackupFilesDir`, porque el sistema purga la caché cuando le falta espacio. |
| Flatpak, AUR, Google Play | **Desactivado.** El gestor es el dueño de los ficheros, y Play prohíbe que una app se actualice por su cuenta. Se detecta con `FLATPAK_ID` y con una propiedad de canal que fija el build o el lanzador de cada paquete. |
| IzzyOnDroid | Por decidir en [[Canal Android IzzyOnDroid]] según su política. Por defecto, la misma APK con el actualizador. |

### 4. Lo que no cambia de ADR-0011

Licencia, identificador, GitHub Releases generados por CI como única fuente de
artefactos, canales, firma de Android, SignPath como mejora para la **primera**
instalación en Windows y Google Play aplazado. Lo único que se sustituye es la
política de actualización: la columna *Actualización* de su §4, la frase de que
el aviso nunca descarga ni ejecuta nada y la idea de que solo los canales
externos actualizan.

## Alternativas consideradas

- **Solo avisar (plan anterior)**: descartado por el usuario. Obliga a instalar a
  mano en cada versión.
- **Esperar a winget, AUR y Flathub**: descartado. Dependen de terceros, no
  cubren el MSI ni el `.deb` sueltos y no llegan a Android sin Obtainium.
- **Conveyor (Hydraulic)**: trae su propio actualizador, pero cambia todo el
  empaquetado (jpackage, MSI, `.deb`) y no cubre Android. Sigue descartado.
- **Confiar solo en HTTPS y en `SHA256SUMS`**: descartado. El hash viaja en el
  mismo Release que el binario, así que no protege si se compromete la cuenta
  de GitHub.
- **minisign o GPG en lugar de Ed25519 a pelo**: el formato es lo de menos.
  Ed25519 ya está en la JVM y en Bouncy Castle, que la app ya trae, y no
  añade dependencias.
- **Instalar aunque haya pestañas abiertas**: descartado. Va contra el pilar de
  sesiones que no se pierden.

## Consecuencias

- Positivas: cada versión llega sola a Windows, Linux y Android sin depender
  de AUR, winget ni SignPath. Las betas llegan al usuario en cuanto se
  publican. La firma propia protege más que el `SHA256SUMS` y no cuesta nada.
- Negativas / compromisos:
  - La clave privada de actualizaciones es tan crítica como el keystore. Si se
    filtra, alguien puede actualizar todas las instalaciones. Si se pierde, hace
    falta una versión que se instale a mano.
  - Hay tres actualizadores que mantener y probar.
  - `.deb` y `.rpm` siempre piden la contraseña.
  - La primera actualización en Android pide confirmación y el permiso de
    instalar apps.
  - Si un día hay usuarios ajenos, quizá convenga revisar que las betas vengan
    incluidas por defecto.
  - Una beta rota se instala sola: la siguiente beta tiene que poder arreglarla
    por el mismo camino, así que el actualizador debe ser lo más estable del
    código.
- Aceptada el 2026-10-16: ADR-0011 pasa a `Reemplazada` por esta. Lo único
  que cambia es su política de actualización; el resto sigue vigente tal como
  dice el §4 de esta ADR. La tarea [[Actualización automática de la app]] deja
  de depender del resto de la fase 5.
