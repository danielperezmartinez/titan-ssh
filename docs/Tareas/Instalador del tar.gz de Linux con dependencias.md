---
Nombre: 'Instalador del tar.gz de Linux con dependencias'
Estado: 'Pendiente'
Resumen: 'Un script install.sh publicado con cada Release que instala el tar.gz de Linux de una vez: instala las librerías que falten con el gestor de la distribución, comprueba que haya un proveedor de Secret Service, descarga y verifica el tar.gz y lo deja en ~/.local/opt/titan-ssh con su .desktop e iconos. Además, la app avisa con claridad cuando no hay Secret Service, y el actualizador comprueba que la carpeta que va a sustituir es de verdad la de la app. Salió al instalar la beta.20 en un Arch con qtile. Para después de 1.0.0 (1.1.0).'
Decisiones: 'El 2026-10-16 el usuario pide la tarea y queda fuera de 1.0.0: no afecta a que las actualizaciones lleguen solas y es una función nueva (SemVer: 1.1.0).'
Bloqueada: []
Fecha de creación: 2026-10-16T19:00:00+02:00
Última modificación: 2026-10-16T19:00:00+02:00
---

# Instalador del tar.gz de Linux con dependencias

## Objetivo

Que instalar titan-ssh desde el `tar.gz` en cualquier distribución sea un solo
comando, sin tener que averiguar a mano las dependencias. Hoy:

- `.deb` y `.rpm` traen sus dependencias de librerías: jpackage las calcula al
  empaquetar (ver [[Configuración de release del escritorio]]).
- AUR y Flatpak las resolverían, pero no están publicados
  ([[Canal Arch Linux AUR]], [[Canal Linux Flatpak en Flathub]]).
- El `tar.gz` no trae nada: el usuario tiene que saber qué librerías pedir y
  dónde descomprimirlo para que el actualizador lo sustituya
  ([[ADR-0025 Actualización automática de la app]] §3: la carpeta de la app y la
  que la contiene tienen que ser del usuario).
- Ningún formato cubre el **proveedor de Secret Service**, que no es una
  librería. En escritorios mínimos (qtile, i3, Hyprland…) no hay ninguno y
  `DesktopSecretStore` falla cerrado: la app arranca, pero no guarda
  credenciales.

Salió el 2026-10-16 al instalar la beta.20 en un portátil con Arch y qtile: las
librerías ya estaban, pero hubo que instalar `gnome-keyring` y elegir la carpeta
a mano.

## Criterios de finalización

- **`install.sh`** como asset de cada Release (y en `SHA256SUMS`), escrito en
  `sh` POSIX:
  - Detecta el gestor (`pacman`, `apt`, `dnf`, `zypper`), comprueba qué
    librerías faltan (las de `depends` del PKGBUILD de AUR: `alsa-lib`,
    `fontconfig`, `libglvnd`, `libx11`, `libxext`, `libxi`, `libxrender`,
    `libxtst`, `hicolor-icon-theme`, con sus nombres en cada distribución) y
    las instala con `sudo`, enseñando antes el comando.
  - Comprueba que haya un `org.freedesktop.secrets` en el bus de la sesión y, si
    no, propone instalar uno (`gnome-keyring` por defecto) y explica cómo se
    desbloquea.
  - Descarga el `tar.gz` de la última versión y lo verifica con
    `titan-update.json` y su firma Ed25519 (no solo con `SHA256SUMS`, que viaja
    en el mismo Release).
  - Lo instala en `~/.local/opt/titan-ssh/` (o en la carpeta que se le pase),
    sin `sudo`, y escribe el `.desktop` con la ruta absoluta del lanzador y los
    iconos en `~/.local/share/icons/hicolor/`. No crea enlaces en
    `~/.local/bin` (ver el criterio del actualizador).
  - Si ya hay una instalación, no la pisa sin preguntar.
- **Aviso en la app** cuando no hay Secret Service: un mensaje claro de por qué
  no se guardan las credenciales y qué instalar, en lugar de un fallo genérico.
- **Actualizador más estricto** (`DesktopInstall.detectLinux`): resolver la ruta
  real del lanzador y comprobar que la carpeta raíz es una imagen de la app
  (`bin/titan-ssh` y `lib/app/`) antes de considerarla `tar.gz` sustituible. Así
  un enlace simbólico o una ruta rara no hace que el instalador mueva otra
  carpeta del usuario.
- README: la sección de Linux enseña el comando del script para el `tar.gz`.
- Probado en contenedores de Arch, Ubuntu y Fedora (con y sin las librerías y
  el Secret Service) y en una instalación real.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
