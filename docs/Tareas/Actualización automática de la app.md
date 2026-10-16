---
Nombre: 'Actualización automática de la app'
Estado: 'En curso'
Resumen: 'La app se actualiza sola en Windows, Linux y Android desde GitHub Releases, betas incluidas, como Claude Code Desktop. Por defecto instala sin preguntar; en Configuración → Ajustes se puede cambiar a preguntar antes. Nunca cierra pestañas abiertas: instala al cerrar o al arrancar la app. Cada Release publica un manifiesto firmado con una clave Ed25519 propia y la app lo verifica antes de instalar. No depende de winget, AUR ni SignPath. Se apaga en Flatpak, AUR y Google Play. Antes se llamaba «Aviso de nueva versión en la app» y solo avisaba.'
Decisiones: 'El 2026-10-16 el usuario decide no esperar a AUR ni a winget: actualización automática en las tres plataformas, betas incluidas, instalando sola por defecto y con opción de preguntar en Ajustes. Diseño en [[ADR-0025 Actualización automática de la app]] (Aceptada el 2026-10-16), que sustituye la política de actualización de [[ADR-0011 Distribución y canales de publicación]]. .deb y .rpm piden la contraseña con pkexec (decisión del usuario). Pendiente: que el usuario cree la clave de firma de actualizaciones.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-10-16T13:00:00+02:00
---

# Actualización automática de la app

## Objetivo

Que cada versión publicada llegue sola a las instalaciones de Windows, Linux y
Android, sin instalar a mano ni esperar a los canales de terceros. El diseño
está en [[ADR-0025 Actualización automática de la app]].

## Criterios de finalización

- **Firma de actualizaciones** 👤: el usuario genera el par Ed25519 y guarda la
  clave privada en GitHub Secrets (`TITAN_UPDATE_SIGNING_KEY`) y en su copia de
  seguridad. La clave pública va en el código.
- **CI**: `release.yml` genera `titan-update.json`, con la versión y el nombre
  y el SHA-256 de cada artefacto, y su firma `titan-update.json.sig`. Los dos
  van como assets del Release. La firma se comprueba en el mismo job.
- **Comprobación**: la app consulta la API de GitHub Releases, sin
  autenticación ni datos del usuario, al arrancar y después como mucho cada 6
  horas. Elige la versión más alta, betas incluidas. Verifica la firma, que la
  versión sea mayor que la instalada y el SHA-256. Si algo falla, no instala
  nada y no muestra errores.
- **Ajuste** en Configuración → Ajustes (`AppSettings`): `Instalar
  automáticamente` (por defecto) o `Preguntar antes de instalar`. Botón
  **Buscar ahora**, y la versión pendiente visible en Acerca de.
- **Nunca interrumpe pestañas**: en automático instala al cerrar la app o al
  arrancarla, sin pestañas abiertas. En el modo de preguntar avisa de lo que
  se perdería.
- **Windows**: el MSI por usuario se instala con `msiexec /qn` desde un proceso
  auxiliar que espera a que la app se cierre y la reabre. No pide permisos de
  administrador.
- **Linux**: el `tar.gz` se sustituye a sí mismo si su carpeta es del usuario.
  `.deb` y `.rpm` se instalan con `pkexec`, que pide la contraseña.
- **Android**: `PackageInstaller` con `REQUEST_INSTALL_PACKAGES`. La primera
  vez pide confirmación. Hay que comprobar en el emulador si las siguientes
  pueden ir sin ella (`USER_ACTION_NOT_REQUIRED`, Android 12 o superior).
- **Canales**: desactivado en Flatpak (`FLATPAK_ID`), AUR y Google Play, con una
  propiedad de canal fijada en el build o en el lanzador. Activo en el MSI
  instalado por winget.
- Código común en `commonMain` detrás de una interfaz. Red, instalación y
  detección de canal en los source sets de plataforma (regla de dependencias
  del [[README]]). La petición saliente se documenta en el README.
- **Prueba de punta a punta** en las tres plataformas: de una versión a la
  siguiente con el modo automático y con el de preguntar. También se prueba que
  rechaza un manifiesto mal firmado, un SHA-256 que no cuadra y una versión
  anterior. Se prueba sin publicar nada, con un origen de pruebas, y después
  con dos betas reales.
- Sugerida al usuario, a demanda: una auditoría de seguridad del actualizador
  antes de dar la tarea por cerrada (regla de [[Auditorías de seguridad]]).

## Verificación

Hecho el 2026-10-16 en la rama `worktree-auto-update`, antes de publicar nada.
Se probó con versiones de prueba `0.9.0-beta.N`, una clave Ed25519 desechable
(`-PtitanUpdateExtraKeys`) y un origen local que imita la API de Releases
(`-PtitanUpdateApi`). Los manifiestos se firmaron con el script real del
workflow.

- **Tests**: 17 en `UpdateEngineTest`. Cubren el orden de versiones, la versión
  más alta con betas, borradores y Releases sin manifiesto, y el rechazo de una
  firma de otra clave, de un manifiesto modificado, de un manifiesto antiguo bajo
  un tag nuevo y de un paquete que no cuadra. También cubren las descargas que
  sobreviven a un reinicio, el paquete borrado antes de instalar y la política en
  escritorio y en Android. Un fixture firmado con OpenSSL 3 comprueba que la app
  acepta lo que firma CI. La suite de escritorio pasa entera salvo
  `DesktopSecretStoreTest`, que falla en la sesión 0 sin Credential Manager, como
  ya se sabía.
- **`tools/release/update-manifest.sh`** en Ubuntu 24.04: escribe y firma el
  manifiesto, y falla sin el secreto o con una clave que no esté en
  `shared/update-keys.txt`.
- **Windows**, con un MSI de prueba con otro nombre y otro `upgradeUuid`, para no
  tocar la instalación real. Instalado sin elevar, la beta.1 descargó la beta.2 y,
  al cerrar la ventana, se instaló sola sin UAC (0.9.31 → 0.9.32). El instalador
  con `-Relaunch`, el de «Instalar y reiniciar», instaló la beta.3 y reabrió la
  app nueva, que borró su descarga.
- **Linux**, con el script de instalación en contenedores: el `tar.gz` se
  sustituye y se reabre; un archivo roto conserva la versión anterior; `.deb` con
  `apt-get` (Ubuntu 22.04) y `.rpm` con `dnf` (Fedora 42) se actualizan, con
  `pkexec` simulado. Una contraseña rechazada (126) no reabre la app.
- **Android**, en el emulador `Pixel_9_Pro_XL`: el `[^]` de la cabecera, el
  botón «Permitir instalar» que abre el ajuste del sistema, y la primera
  actualización con la confirmación del sistema. Después, la beta.4 se instaló
  sola con la app en segundo plano y sin pestañas, sin ningún diálogo.
  - Salieron tres fallos y están corregidos: el aviso de «cancelada» de la sesión
    anterior se tomaba por el de la nueva; faltaba
    `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; y el sistema purgaba el APK de la
    caché por falta de espacio, así que ahora espera en `noBackupFilesDir`.
  - Google Play Protect frenó cada APK de prueba («app que no ha visto antes»)
    y no deja instalarla sin enviarla a Google. En el emulador se desactivó el
    análisis.

- **Endurecimiento antes de publicar**, a petición del usuario. El instalador
  vuelve a comprobar el SHA-256 del manifiesto justo antes de instalar. Con
  `.deb` y `.rpm` lo hace como root sobre una copia en una carpeta de root, e
  instala esa misma copia; así otro proceso del usuario no puede cambiar el
  paquete entre la verificación y la instalación, que pide la contraseña de
  administrador. Si no coincide, sale con el código 3 y no instala ni reabre la
  app. Probado en Ubuntu 22.04 (17 de 17, con un `.tar.gz` y un `.deb`
  cambiados), en Fedora 42 y con el script de Windows.

**Pendiente**:

- Hecho el 2026-10-16: el usuario creó la clave de firma real, la guardó en
  el secreto `TITAN_UPDATE_SIGNING_KEY` y en su gestor de contraseñas. Su
  clave pública está en `shared/update-keys.txt` y el fichero privado se borró
  del disco sin pasar por git.
- Comprobarlo con versiones reales. Decidido el 2026-10-16: se publica
  `0.1.0-beta.20`, la última beta y la primera con el actualizador; el usuario
  la instala a mano en el PC y en el Pixel. Después se publica `1.0.0`, que
  tiene que llegar sola a los dos, con el aviso de Play Protect en el Pixel si
  sale. Con eso la tarea pasa a `Hecha`.

## Resultado

<Se rellena al completar.>
