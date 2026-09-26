---
Nombre: 'Canal Windows winget'
Estado: 'En curso'
Resumen: 'Publicar titan-ssh en winget (gratis), el gestor de paquetes de Windows, con manifiestos en microsoft/winget-pkgs que apuntan al MSI del GitHub Release: DanielPerezMartinez.TitanSSH para las estables y DanielPerezMartinez.TitanSSH.Beta para las pre-releases. winget-pkgs acepta MSI sin firmar. El primer envío de cada ID se hace a mano (los manifiestos de la beta.2 ya están escritos y validados en desktopApp/packaging/winget/) y los siguientes los abre el job winget del workflow con Komac, desde un fork de winget-pkgs del usuario y un token clásico con public_repo. Fork creado y primer PR abierto (microsoft/winget-pkgs#441824, 2026-09-26). CLA firmado. Faltan la validación y la fusión, la variable WINGET_ENABLED (tras la fusión) y la prueba con winget install/upgrade.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4. Decisiones del usuario (2026-09-26): PackageIdentifier DanielPerezMartinez.TitanSSH (Moniker titan-ssh), y las pre-releases van a un ID aparte, DanielPerezMartinez.TitanSSH.Beta, como Termius.Termius.Beta; mismo criterio que en [[Canal Arch Linux AUR]]. Se usa Komac fijado por hash en vez de winget-releaser, que descarga Komac sin fijar la versión.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-26T20:25:00+02:00
---

# Canal Windows winget

## Objetivo

`winget install` y `winget upgrade` de titan-ssh en Windows, sin tienda ni
firma de pago.

## Criterios de finalización

- [x] `PackageIdentifier` decidido con el usuario: `DanielPerezMartinez.TitanSSH`
  para las estables y `DanielPerezMartinez.TitanSSH.Beta` para las
  pre-releases. No se puede cambiar fácilmente después.
- [x] El MSI cumple lo que winget necesita: instalación silenciosa,
  `UpgradeCode` estable (el `upgradeUuid` de
  [[Configuración de release del escritorio]]) y la URL fija del Release.
- [x] Política vigente: se acepta un **MSI sin firmar** (ver **Contexto**). No
  hace falta esperar a [[Firma de código Windows con SignPath Foundation]].
- [ ] Primer manifiesto (`DanielPerezMartinez.TitanSSH.Beta` `0.1.0-beta.2`)
  enviado ([microsoft/winget-pkgs#441824](https://github.com/microsoft/winget-pkgs/pull/441824), 2026-09-26) y fusionado.
- [x] Automatización: job `winget` del release, que abre el PR de la versión
  nueva con Komac.
- [ ] 👤 Secreto `WINGET_TOKEN` cargado y un PR abierto por el job con la
  siguiente beta.
- [ ] 👤 Probado: `winget install` y `winget upgrade` en el Windows del
  usuario.
- [ ] Sección de winget en el `README.md` de la raíz, cuando el paquete exista.
- Con la primera estable: primer envío manual de `DanielPerezMartinez.TitanSSH`
  (los mismos manifiestos, con `Moniker: titan-ssh` y sin "Beta" en
  `PackageName`).
- Al acabar [[Firma de código Windows con SignPath Foundation]], el MSI
  firmado sustituye al actual con la misma URL. En winget solo cambia el
  hash, que Komac calcula.

## Contexto

Investigación del 2026-09-26 (políticas y fuentes de winget-pkgs, winget-cli y
Komac):

- **Sin firmar**: las políticas de winget-pkgs no piden firma de código. La
  validación comprueba el esquema, la URL y el hash, analiza el instalador con
  varios antivirus e instala y desinstala en una VM con Defender. SmartScreen
  puede seguir avisando a quien ejecute el MSI a mano.
- **Pre-releases**: no hay una norma escrita, pero la convención es un ID
  aparte (`.Beta`, `.Preview`). winget ordena `0.1.0-beta.2` por debajo de
  `0.1.0`, y alpha < beta < rc.
- **Versión del MSI distinta**: el `ProductVersion` del MSI es
  `X.Y.(Z*100+S)` (`0.1.32` para `0.1.0-beta.2`, ver
  [[Versionado único desde tag de git]]). Si difiere de `PackageVersion`,
  `Authoring.md` exige `AppsAndFeaturesEntries.DisplayVersion` en todos los
  manifiestos. Cada `DisplayVersion` tiene que ser único, y la fórmula ya lo
  garantiza. Komac lo rellena solo al leer el MSI.
- **MSI por usuario**: `Scope: user` e `InstallerType: wix` (jpackage usa
  WiX). winget pasa `/quiet` sin más opciones.
- **Automatización**: `winget-releaser` es una acción que ejecuta Komac,
  descargado sin fijar la versión. Aquí se usa Komac directamente (v2.16.0,
  binario de Linux verificado por SHA-256). Necesita un **token clásico** con
  `public_repo` (los de grano fino no pueden abrir el PR) y un **fork**
  `<usuario>/winget-pkgs` ya creado: Komac no lo crea. Solo actualiza
  paquetes que ya existen, así que el primer envío de cada ID es manual.
- **Primer PR**: lo revisa un moderador (una o dos semanas) y pide firmar una
  vez el CLA de Microsoft.
- El `UpgradeCode` y el `DisplayName` (`titan-ssh`) son los mismos en los dos
  IDs, así que instalar la estable encima de una beta la actualiza en el
  sitio.

## Pendiente

1. [x] Fork `danielperezmartinez/winget-pkgs` de <https://github.com/microsoft/winget-pkgs>
   (2026-09-26).
2. [ ] Primer PR, abierto el 2026-09-26: [#441824](https://github.com/microsoft/winget-pkgs/pull/441824). Los tres ficheros de
   `desktopApp/packaging/winget/manifests/d/DanielPerezMartinez/TitanSSH/Beta/0.1.0-beta.2/`
   se subieron a esa misma ruta (`manifests/d/…`) en la rama
   `danielperezmartinez-patch-1`, idénticos a los validados. CLA firmado el mismo día (el bot quitó `Needs-CLA` y `license/cla` está en verde), y el validador puso la etiqueta `New-Package`. Falta que
   acabe la validación automática y lo revise un moderador. La casilla de `winget install --manifest` quedó sin marcar porque no se probó.
3. [x] Token clásico `titan-ssh winget (Komac)`, con solo `public_repo` y con caducidad,
   cargado en el secreto `WINGET_TOKEN` (2026-09-26). Ese token puede
   escribir en todos los repos públicos de la cuenta: hay que renovarlo al
   caducar y revocarlo si se deja de usar.
4. 👤 Con el PR fusionado, crear la variable del repositorio
   `WINGET_ENABLED` = `true` (Settings → Secrets and variables → Actions →
   Variables). Hasta entonces el job `winget` no se ejecuta, porque Komac
   fallaría con un paquete que aún no existe.
5. La siguiente beta prueba el job, y el usuario
   comprueba `winget install DanielPerezMartinez.TitanSSH.Beta` y, con la
   beta siguiente, `winget upgrade`.

## Verificación

- Manifiestos de la beta.2 (esquema 1.12.0): `winget validate` (winget
  v1.29.380) da `Validación del manifiesto correcta`. El `ProductCode`,
  `UpgradeCode`, `Manufacturer`, `ProductName` y `ProductVersion` se leyeron
  de la tabla `Property` del MSI publicado, y el hash, del propio fichero
  (coincide con `SHA256SUMS`).
- Job `winget`: actionlint sin avisos. El hash de Komac se comprobó contra el
  `digest` del asset en su Release. Sin probar en CI hasta que existan el
  paquete y el secreto.

## Resultado

- `desktopApp/packaging/winget/manifests/…/Beta/0.1.0-beta.2/`: los tres
  manifiestos (`version`, `installer`, `defaultLocale`) del primer envío.
  Quedan como referencia para el primer envío de la estable. Las versiones
  siguientes las genera Komac a partir de la última publicada en winget-pkgs.
- **Job `winget`** (tras `publish`): instala Komac comprobando su SHA-256 y
  ejecuta `komac update <ID> --version <versión> --urls <MSI del Release>
  --release-notes-url <Release> --submit`, con `GITHUB_TOKEN` =
  `WINGET_TOKEN`. El ID lleva `.Beta` si la versión es pre-release. Si falta
  el secreto, el job falla con un error claro.
