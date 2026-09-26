---
Nombre: 'Canal Arch Linux AUR'
Estado: 'Pendiente'
Resumen: 'Paquetes en AUR (gratis) para Arch y derivadas, que el usuario usa: titan-ssh-bin para las versiones estables y titan-ssh-beta-bin para las pre-releases. El PKGBUILD descarga el tar.gz del GitHub Release, verifica su sha256 e instala la app con JRE embebido en /opt, un lanzador, el .desktop, los iconos y la licencia; los proveedores de Secret Service van como optdepends. La plantilla vive en desktopApp/packaging/aur/ y el job aur del workflow de release la rellena, la construye y la sube a AUR con una clave SSH guardada como secreto. Hecho y probado en un contenedor Arch. Bloqueado desde el 2026-09-26 porque AUR tiene cerrado el registro de cuentas nuevas; la clave SSH ya está creada y cargada en GitHub. Queda en Pendiente para retomarla cuando reabran el registro: la sección Pendiente lista los pasos (cuenta, clave pública, primer envío, variable AUR_ENABLED, prueba con yay en el Arch del usuario y README).'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4. Decisión del usuario (2026-09-26): las pre-releases van a un paquete aparte, titan-ssh-beta-bin, para que quien use la estable no reciba betas; es el mismo criterio que en [[Canal Windows winget]]. pkgver es la versión sin el guion (0.1.0beta.2), que pacman ordena antes de 0.1.0.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-26T20:20:00+02:00
---

# Canal Arch Linux AUR

## Objetivo

Instalar y actualizar titan-ssh en Arch con `yay`/`paru` como cualquier otro
paquete.

## Criterios de finalización

- [ ] 👤 **Cuenta de AUR creada por el usuario** (el agente no crea cuentas) con
  una clave SSH dedicada, y la clave privada cargada en GitHub como secreto
  `AUR_SSH_PRIVATE_KEY`.
- [x] Plantilla `desktopApp/packaging/aur/PKGBUILD.in`, que `render.sh`
  convierte en el PKGBUILD de un Release:
  - `source` = `tar.gz` del Release, `sha256sums` reales y
    `license=('GPL-3.0-or-later')`.
  - Instala en `/opt/titan-ssh`, crea el lanzador `/usr/bin/titan-ssh` (enlace
    al de jpackage), el `.desktop` en `/usr/share/applications`, los iconos
    hicolor y la licencia y los avisos de terceros en `/usr/share/licenses`.
  - `depends`: las librerías que enlazan el JRE y Skiko (ver **Resultado**).
    `optdepends`: `gnome-keyring`, `kwallet` o `keepassxc` como proveedor de
    Secret Service para el `SecretStore`
    ([[Almacenamiento seguro de credenciales]]).
  - `.SRCINFO` generado con `makepkg --printsrcinfo`. namcap sin errores
    reales (los dos que da son propios de un paquete `-bin` con JRE, ver
    **Verificación**).
- [x] Job `aur` en `.github/workflows/release.yml` que, tras publicar el
  Release, genera el PKGBUILD, lo construye y lo sube al paquete del canal.
- [ ] Primer envío: `titan-ssh-beta-bin` con `v0.1.0-beta.2` (ver
  **Pendiente**).
- [ ] 👤 Probado en el Arch del usuario: instalar con `yay`/`paru`, actualizar a
  la siguiente beta (la sube el job) y desinstalar.
- [ ] Sección de AUR en el `README.md` de la raíz, cuando el paquete exista.
- Opcional, más adelante: `titan-ssh` compilado desde fuente.

## Pendiente: retomar cuando AUR reabra el registro

**Bloqueo externo (2026-09-26)**: AUR tiene cerrado el registro de cuentas
nuevas por una oleada de altas automáticas, sin cola ni fecha de reapertura.
Se anuncia en la lista aur-general y en las noticias de Arch
(<https://archlinux.org/news/>). No hay que reintentar el registro con
scripts. Todo lo que no necesita la cuenta está hecho, y el job `aur` no se
ejecuta mientras la variable `AUR_ENABLED` no valga `true`, así que las
releases salen igual.

Ya hecho:

- [x] Clave SSH de AUR (ed25519). La privada está
  en el gestor de contraseñas del usuario y en el secreto
  `AUR_SSH_PRIVATE_KEY` de GitHub; la pública, en el mismo gestor.
- [x] PKGBUILD, `render.sh`, `known_hosts` y job `aur`, probados en un
  contenedor Arch (ver **Verificación**).

Pasos para retomarlo, en orden:

1. [ ] 👤 Comprobar que el registro está abierto:
   <https://aur.archlinux.org/register>.
2. [ ] 👤 Crear la cuenta de AUR (el agente no crea cuentas).
3. [ ] 👤 En **My Account → SSH Public Key**, pegar la clave pública (está
   en el gestor de contraseñas del usuario) y guardar.
4. [ ] Primer envío de `titan-ssh-beta-bin`, que crea el paquete. La clave
   privada no está en el PC del agente, así que hay dos opciones:
   - 👤 El usuario, desde su Arch con esa clave, genera los
     ficheros con `render.sh` y `makepkg --printsrcinfo` para la última beta
     publicada, los sube con `git push` a
     `ssh://aur@aur.archlinux.org/titan-ssh-beta-bin.git` y el agente le da
     los comandos.
   - O se salta este paso y el job crea el paquete con la próxima beta (paso
     5). En ese caso la actualización con `yay` se prueba con la beta
     siguiente.
5. [ ] 👤 Crear la variable del repositorio `AUR_ENABLED` = `true` (GitHub →
   Settings → Secrets and variables → Actions → Variables).
6. [ ] Publicar la siguiente beta (cuando el usuario lo pida) y comprobar que el
   job `aur` acaba bien y que el paquete aparece en
   <https://aur.archlinux.org/packages/titan-ssh-beta-bin>.
7. [ ] 👤 En el Arch del usuario: `yay -S titan-ssh-beta-bin` y arrancar la
   app. Con la beta siguiente, `yay -Syu` para probar la actualización, y
   `yay -R titan-ssh-beta-bin` para comprobar que se desinstala limpio.
8. [ ] Añadir la sección de AUR al `README.md` de la raíz y pasar la tarea a
   `Hecha`. `titan-ssh-bin` lo crea el job solo con la primera estable.

## Verificación

**Contenedor `archlinux:base-devel` limpio** (2026-09-26), con los `tar.gz`
publicados de `v0.1.0-beta.1` y `v0.1.0-beta.2`:

- `makepkg` construye el paquete y `pacman -U` lo instala. Al construir la
  beta.2 encima, pacman lo toma como actualización (`0.1.0beta.1-1` →
  `0.1.0beta.2-1`) y `pacman -Qkk` no ve ficheros alterados.
- La app, lanzada con `titan-ssh` desde `/usr/bin` bajo Xvfb, sigue en marcha
  a los 20 s. Skiko cae a renderizado por software por falta de GPU, como en
  las pruebas del `.deb` y el `.rpm`.
- `pacman -R` no deja nada en `/opt`.
- Orden de versiones con `vercmp`: `0.1.0alpha.3` < `0.1.0beta.1` <
  `0.1.0beta.2` < `0.1.0beta.10` < `0.1.0rc.1` < `0.1.0` < `0.1.1beta.1`. Con
  `_` (`0.1.0_beta.2`) la beta quedaría por encima de la `0.1.0`, y por eso se
  quita el guion.
- namcap: dos errores que no lo son. `ELF files outside of a valid path
  ('opt/')` sale en todo paquete `-bin` que se instala en `/opt`, y `Dependency
  java-runtime detected and not included` se debe a que el JRE va embebido.
  El resto son avisos (binarios del JRE sin `strip`, `libjvm.so` embebida y
  librerías enlazadas que no se usan). El PKGBUILD pasa sin avisos.
- Los pasos del job `aur` (render, `makepkg -fd` como usuario sin privilegios
  y `.SRCINFO`) se reprodujeron en el contenedor con la beta.2: sale
  `titan-ssh-beta-bin-0.1.0beta.2-1-x86_64.pkg.tar.zst`. `render.sh` con
  `0.1.0` genera `titan-ssh-bin` y rechaza versiones mal formadas. actionlint
  y shellcheck no dan avisos.
- Los nombres `titan-ssh-bin` y `titan-ssh-beta-bin` están libres en AUR.

## Resultado

- **Ficheros** (`desktopApp/packaging/aur/`):
  - `PKGBUILD.in`, la plantilla, con `@PKGNAME@`, `@PKGDESC@`, `@VERSION@`,
    `@PKGVER@` y `@SHA256@`.
  - `render.sh <versión> <sha256> <dir>`, que elige el canal por la versión
    (con `-`, `titan-ssh-beta-bin`) y valida la versión y el hash.
  - `known_hosts`, con las claves de host de `aur.archlinux.org`, comprobadas
    el 2026-09-26 contra las huellas que publica la portada de AUR.
- **Dependencias**: `alsa-lib`, `fontconfig`, `glibc`, `hicolor-icon-theme`,
  `libglvnd`, `libx11`, `libxext`, `libxi`, `libxrender` y `libxtst`. Salen
  de las `NEEDED` de las librerías del `tar.gz`. `libfreetype` va dentro del
  JRE. Los dos canales proporcionan `titan-ssh` y chocan con él, así que solo
  se puede tener uno instalado.
- **Job `aur`** (tras `publish`, en un contenedor `archlinux:base-devel`):
  calcula el SHA-256 del `tar.gz` del artefacto de CI. `makepkg` descarga el
  del Release y lo compara con ese hash, así que lo que reciben los usuarios de
  AUR es lo que compiló CI. Después clona el paquete por SSH, con la clave de
  host fijada (`StrictHostKeyChecking=yes`), y sube `PKGBUILD` y `.SRCINFO`
  a `master` si han cambiado. Si falta el secreto, el job falla con un error
  claro. El Release ya estará publicado y solo falta reintentarlo.
