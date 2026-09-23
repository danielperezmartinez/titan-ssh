---
Nombre: 'Distribución y canales de publicación'
Número: 11
Estado: 'Aceptada'
Resumen: 'Cómo se distribuye titan-ssh en Windows, Linux y Android con coste cero (decisión del usuario, 2026-09-23: el proyecto no es rentable y no se paga por distribuir). El código se publica con licencia GPL-3.0-or-later (titular Daniel Pérez Martínez) y el identificador de la app pasa a io.github.danielperezmartinez.* (no se usa gar.im, que no es del usuario). Una única fuente de artefactos, los GitHub Releases generados por GitHub Actions a partir de un tag vX.Y.Z; encima de ellos, canales gratuitos con actualización automática. Windows usa MSI y winget, sin firmar al principio y firmado con SignPath Foundation (gratuito para open source) cuando sea posible. Linux usa Flatpak en Flathub como canal principal (cubre Bazzite y el resto), AUR para Arch y .deb, .rpm y tar.gz en Releases. Android usa una APK firmada en Releases, instalable con Obtainium, más IzzyOnDroid como canal público. Google Play queda aplazado (25 $ únicos) pero se preparan la clave y el AAB para no cerrarlo.'
Decisión: 'Licencia GPL-3.0-or-later; ID io.github.danielperezmartinez.*; artefactos solo en GitHub Releases generados por CI desde un tag; canales gratuitos encima (winget, Flathub, AUR, Obtainium, IzzyOnDroid); firma de Windows con SignPath Foundation cuando se conceda, sin pagar certificados; Google Play aplazado sin cerrar la puerta (AAB y clave de subida preparados).'
Consecuencias: 'Distribución a coste cero y con actualizaciones automáticas en todos los canales salvo el MSI suelto. A cambio, Windows mostrará el aviso de SmartScreen hasta obtener la firma de SignPath (que depende de su aprobación), cada canal tiene su propio proceso de revisión y su mantenimiento, y Flathub exige permisos de sandbox y probablemente compilar desde el código fuente. La clave de firma de Android pasa a ser un secreto crítico: si se pierde, los usuarios de Obtainium/IzzyOnDroid no pueden actualizar sin reinstalar. El cambio de identificador obliga a renombrar los paquetes Kotlin antes de la primera versión pública.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# ADR-0011 · Distribución y canales de publicación

## Contexto

Hasta ahora titan-ssh no se distribuye por ningún canal: las pruebas se hacen en
local o pasando la APK a mano. La regla 4 del [[README]] fijaba Google Play y el
empaquetado nativo de Compose Multiplatform, pero dejaba el detalle "por afinar".

Restricciones fijadas por el usuario (2026-09-23):

- **Coste cero.** El proyecto no es rentable; no se pagan certificados, cuotas
  ni suscripciones de firma.
- **Repositorio público** en GitHub (`danielperezmartinez/titan-ssh`), pero
  **sin licencia**: legalmente es "todos los derechos reservados", y casi todos
  los canales gratuitos exigen una licencia libre.
- **Primera audiencia: el propio usuario**, con la puerta abierta a
  usuarios públicos.
- **Linux**: el usuario usa **Arch** y **Bazzite** (Fedora Atomic, inmutable:
  ni `.deb` ni `.rpm` son el camino natural; lo propio es Flatpak). Objetivo:
  que funcione en las distribuciones más usadas.
- **Android**: cuenta personal de Google sin la cuota de 25 $ pagada, así que no
  hay Play Console.
- **Dominio**: `gar.im` **no** es del usuario y no quiere usarlo; hoy aparece
  como `im.gar.titanssh` en el `applicationId` y en los paquetes Kotlin.

Restricciones técnicas encontradas en el código:

- jpackage (usado por Compose Desktop) **no compila para otra plataforma**: el
  MSI solo se genera en Windows y el `.deb`/`.rpm` en Linux, así que hace falta CI
  con varios sistemas operativos.
- `desktopApp` no tiene `upgradeUuid` (el MSI no se actualizaría sobre sí mismo),
  la versión de escritorio (`1.0.0`) y la de Android (`0.1.0`, `versionCode 1`)
  no coinciden, no hay iconos, y los binarios del agente se empaquetan en modo
  "si se puede" (sin Go, la build sale sin agente).
- Android no tiene configuración de firma de release ni R8.

## Decisión

### 1. Licencia y titularidad

**GPL-3.0-or-later**, titular **Daniel Pérez Martínez**. Aplica al cliente KMP
y al agente Go. Ver [[Licencia GPL-3.0-or-later del proyecto]].

### 2. Identificador de la app

`io.github.danielperezmartinez.*`, gratis y verificable en Flathub con la cuenta
de GitHub. No se compra dominio. Android: `applicationId`
`io.github.danielperezmartinez.titanssh`; el ID de Flatpak sigue la forma que
exija Flathub para `io.github`. Se hace **antes de la primera versión pública**,
porque en Android el `applicationId` no se puede cambiar sin perder las
actualizaciones. Ver [[Cambiar el identificador de la app a io.github]].

### 3. Fuente única de artefactos: GitHub Releases generados por CI

- **GitHub Actions** (gratis en repositorios públicos), disparado por un tag
  `vX.Y.Z`. De ese tag sale la versión única de las tres plataformas
  ([[Versionado único desde tag de git]]).
- Un tag con sufijo (`v0.2.0-beta.1`) genera un **pre-release** (canal interno:
  el usuario); sin sufijo, una versión **estable**.
- Artefactos: MSI, `.deb`, `.rpm`, `tar.gz` (imagen de la app), APK firmada, AAB
  (para el futuro Play) y `SHA256SUMS`.
- Ver [[Pipeline de release en GitHub Actions]].

### 4. Canales por plataforma (todos gratuitos)

| Plataforma | Canal | Actualización | Tarea |
|---|---|---|---|
| Windows | MSI en Releases | Aviso en la app | [[Configuración de release del escritorio]] |
| Windows | winget | `winget upgrade` | [[Canal Windows winget]] |
| Linux (todas, incl. Bazzite) | **Flatpak en Flathub** (principal) | Automática | [[Canal Linux Flatpak en Flathub]] |
| Arch y derivadas | AUR `titan-ssh-bin` | Gestor de AUR | [[Canal Arch Linux AUR]] |
| Debian/Ubuntu, Fedora/openSUSE, resto | `.deb`, `.rpm`, `tar.gz` en Releases | Aviso en la app | [[Configuración de release del escritorio]] |
| Android (el usuario) | APK en Releases + Obtainium | Automática | [[Canal Android Obtainium desde GitHub Releases]] |
| Android (público) | IzzyOnDroid | Automática (clientes F-Droid) | [[Canal Android IzzyOnDroid]] |

Para los canales sin actualizador propio (MSI, `.deb`, `.rpm` y `tar.gz` sueltos) se añade un
aviso de versión nueva en la app ([[Aviso de nueva versión en la app]]).

### 5. Firma

- **Android**: clave de firma propia (keystore) fuera del repositorio, en los
  secretos de CI y con copia de seguridad del usuario. La misma clave servirá
  como **clave de subida** si algún día se usa Play (Play App Signing). Ver
  [[Firma y configuración de release Android]].
- **Windows**: sin firmar al principio (aviso de SmartScreen) y, en cuanto se
  cumplan sus requisitos, **SignPath Foundation** (firma gratuita para open
  source). Ver [[Firma de código Windows con SignPath Foundation]].
- **Linux**: los propios canales verifican la integridad (Flathub firma su
  repositorio; AUR usa checksums). En Releases se publica `SHA256SUMS`.

### 6. Google Play: aplazado, no descartado

Se aplaza por la cuota de 25 $. Se preparan desde ya el AAB y la clave de subida
para que abrirlo más tarde no requiera cambios de build. Ver
[[Publicación en Google Play]].

### 7. Relación con el agente

[[ADR-0010 Empaquetado del agente y descarga bajo demanda]] tiene pendiente el
origen de descarga de los binarios del agente no empaquetados. Ahora que el
repositorio es público y habrá un pipeline de release, **GitHub Releases** pasa
a ser un candidato natural. La elección se cierra en ADR-0010, no aquí. El
pipeline debe poder publicar esos binarios si se elige esa opción.

## Alternativas consideradas

- **Certificado OV/EV de firma para Windows** (unos 200–400 €/año, token físico o
  HSM): descartado por coste.
- **Azure Trusted Signing** (unos 10 $/mes): descartado por coste (y, para
  particulares, con disponibilidad geográfica limitada).
- **Microsoft Store (MSIX)**: la cuenta de particular es gratis y la Store firma
  el MSIX, pero jpackage no genera MSIX y añade un empaquetado aparte; si se sube
  un MSI/EXE, hay que firmarlo uno mismo. Aplazado; se puede reconsiderar si
  SignPath no se concede.
- **Conveyor (Hydraulic)**: gratis para open source, con actualización automática,
  pero añade una herramienta más, no genera Flatpak y no quita el aviso de
  SmartScreen sin certificado. Descartado: winget, Flathub, AUR y Obtainium ya
  actualizan.
- **Snap**: poco usado fuera de Ubuntu y redundante con Flatpak. Descartado.
- **AppImage**: funciona en cualquier distribución, pero aporta poco si ya hay
  Flatpak y `tar.gz`. Opcional, no planificado.
- **F-Droid oficial**: compila desde el código fuente (incluido el agente Go),
  con más exigencias. Aplazado; IzzyOnDroid cubre el canal público de Android
  con menos esfuerzo.
- **Firebase App Distribution** para pruebas: gratis, pero exige cuenta de
  Firebase y una app de Google; Obtainium sobre GitHub Releases es más simple.
- **Google Play ya**: descartado por coste por ahora (sección 6).

## Consecuencias

- Positivas: coste cero; una sola fuente de artefactos reproducible desde CI; el
  usuario recibe las versiones de prueba como actualizaciones normales en
  Android (Obtainium) y en Linux (Flatpak/AUR); cobertura de las
  distribuciones más usadas, incluida Bazzite.
- Negativas / compromisos: aviso de SmartScreen en Windows hasta tener SignPath,
  que depende de su aprobación; cada canal tiene su revisión y su mantenimiento;
  Flathub exige ajustar el sandbox (red, Secret Service, `~/.ssh`, X11 para
  AWT) y seguramente compilar desde el código fuente; la clave de Android es un
  secreto crítico (si se pierde, no hay actualizaciones); el renombrado de
  paquetes toca todo el código.
