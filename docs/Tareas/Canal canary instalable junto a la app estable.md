---
Nombre: 'Canal canary instalable junto a la app estable'
Estado: 'En curso'
Resumen: 'Crear la variante canary de la app según ADR-0027. Es otra app que se instala junto a la estable en Android, Windows y Linux. CI la construye en cada push a main y la publica en el Release rodante canary, y se actualiza sola desde él. Copia de la estable hosts y configuración, con secretos solo en escritorio. Tiene sus propias carpetas, su espacio de secretos y su agente en los destinos. Incluye que el actualizador de la estable deje de instalar pre-releases.'
Decisiones: 'El 2026-10-20 el usuario elige la opción de una app canary aparte, frente a una rama develop con PR o un canal beta en Ajustes, y pide compartir los hosts si no supone un problema de seguridad. Decisión en [[ADR-0027 Canal canary instalable junto a la app estable]], aceptada el 2026-10-20. Icono: el de la estable con la parte azul de la T en el naranja de la app. En Android no se pasan secretos a la canary (lo que interesaba eran las claves hardware, que no se pueden).'
Bloqueada: []
Versión: '1.3.0'
Fecha de creación: 2026-10-20T10:40:00+02:00
Última modificación: 2026-10-20T22:00:00+02:00
---

# Canal canary instalable junto a la app estable

## Objetivo

Que el usuario tenga en sus dispositivos una **canary** que se actualiza sola
con cada commit de `main`, para probar lo que va a publicar antes de crear el
tag `vX.Y.Z`. Sin tocar la instalación estable y sin que llegue a nadie más.
Diseño en [[ADR-0027 Canal canary instalable junto a la app estable]].

## Alcance

1. **Canal de build**: propiedad `titanChannel` (`stable` por defecto,
   `canary`) en el `build.gradle.kts` raíz, expuesta en `BuildInfo`. Actualizar
   su nota del catálogo técnico. De ella salen:
   - Android: sufijo `.canary` del `applicationId` y nombre.
   - Icono en las tres plataformas: el de la estable con la parte azul de la T
     en el naranja de la app.
   - Escritorio: `packageName`, `upgradeUuid` propio del MSI,
     `installationPath`, lanzador.
   - Carpetas de datos y caché, espacio del `SecretStore`, carpeta del agente
     en destinos (`AgentInstall`) y ayudante de escritorio.
2. **Versión canary**: `X.Y.(Z+1)-canary.<run>` y la derivación del
   `versionCode` y la versión del MSI a partir de `<run>`. Ampliar
   `printVersion` y [[Versionado único desde tag de git]].
3. **Actualizador**:
   - Estable: ignorar pre-releases y manifiestos con un `channel` distinto de
     `stable`.
   - Canary: leer solo el Release `canary` y comparar por `<run>`.
   - Campo `channel` en el manifiesto firmado.
   - Test de que el tag `canary` no lo acepta el `AppVersion.parse` de la
     estable.
4. **Workflow**: construir en cada push a `main` y a mano. Compartir la
   compilación con `release.yml` (workflow reutilizable o jobs comunes). Mover
   el tag `canary`, sustituir los assets del Release rodante (APK, MSI,
   `tar.gz`, `titan-update.json` y su `.sig`) y marcarlo como pre-release y no
   *latest*.
5. **Copia de la configuración desde la estable** (un solo sentido):
   - Al primer arranque y con el botón *Copiar de la app estable* en Ajustes.
   - Escritorio: `config.json`, `known_hosts` y secretos al espacio
     `titan-ssh-canary`.
   - Android: `ContentProvider` de solo lectura en la estable con permiso
     `signature`, que da la configuración sin secretos y `known_hosts`. Los
     hosts quedan *pendientes de credencial*. Coordinar con
     [[Exportar e importar la configuración sin secretos]], que necesita la
     misma marca.
6. **Documentación**: regla 4 del [[README]] (canary para probar en
   dispositivo; la estable no instala pre-releases),
   [[Pipeline de release en GitHub Actions]], y ADR-0025 a `Reemplazada` en la
   parte de pre-releases.

## Criterios de finalización

- La canary y la estable están instaladas a la vez en el emulador, en Windows
  y en Linux, cada una con sus datos.
- Un push a `main` actualiza el Release `canary` y la canary instalada se
  actualiza sola a esa build.
- Una estable instalada (1.1.x y la nueva) no ve ni instala la canary.
- La canary copia los hosts de la estable. En escritorio conecta sin volver a
  pedir credenciales. En Android pide la credencial de cada host copiado.
- La canary nunca modifica `config.json`, `known_hosts`, los secretos ni el
  agente de la estable, ni en local ni en los destinos.
- El diff y los workflows no exponen nada de la regla 5.

## Verificación

Hecho el 2026-10-20 en la rama `canary-channel` (falta lo marcado como
pendiente):

- **Tests**: los 460 de escritorio y los del agente en Go. Nuevos:
  - la estable no instala pre-releases, ni el Release `canary`, ni un
    manifiesto con `channel: canary`;
  - la canary instala su Release rodante, no se reinstala la misma build y
    rechaza un manifiesto o una versión estables;
  - `AppVersion` ordena `canary` y no acepta el tag `canary`;
  - `FileStableCopier` copia config, `known_hosts` y secretos sin tocar los
    ficheros de la estable, y no cambia nada si la estable falta o no se puede
    leer;
  - el agente toma su carpeta del canal.

  En modo canary fallan 12 tests que esperan los nombres de la estable, como
  debe ser: CI pasa los tests como build de desarrollo.
- **Gradle**: `printVersion` con `1.1.2-canary.57` da `versionCode 57` y MSI
  `1.1.57`. Una versión canary con el canal estable falla.
- **Paquetes**: el `tar.gz` de la canary lleva `titan-ssh-canary/`, su
  `.desktop` e iconos naranjas. El de la estable sale igual que antes.
  `update-manifest.sh … canary` firma con `channel: canary` y exige solo APK,
  MSI y `tar.gz`. actionlint 1.7.7 no da errores en `release.yml`.
- **Emulador** (estable y canary de debug instaladas a la vez, capturas
  enseñadas al usuario):
  - la canary arranca como *titan-ssh canary*, con el icono del cursor
    naranja, y en el primer arranque copia los hosts y la sesión de la
    estable. El botón *Copiar de la app estable* de Ajustes pide confirmación
    y vuelve a copiar;
  - el proveedor de la estable rechaza a quien no tiene el permiso de firma
    (`adb shell content call` da `SecurityException`);
  - sin secretos: la canary pide la contraseña del host. Con ella conecta a
    nivel 3. En el destino corren dos daemons separados, `titan-ssh` y
    `titan-ssh-canary`, y la estable conserva sus sesiones.

  La primera prueba sacó un fallo: el agente de una build de desarrollo
  (`0.0.0-dev`) deducía el canal de la versión y compartía el estado con la
  estable. Ahora el canal se estampa en el agente con `-X main.channel`.
- **Primer run real** (2026-10-20, run `39466913391`, todo en verde):
  publicó *titan-ssh canary 1.2.1-canary.27* como pre-release, con APK, MSI,
  `tar.gz`, los 13 agentes, `SHA256SUMS` y un `titan-update.json` con
  `channel: canary`. El Release *latest* sigue siendo `v1.2.0`, y `publish`,
  `aur` y `winget` se saltaron.
- **En los dispositivos del usuario** (2026-10-20, canary `1.2.1-canary.27`):
  - Linux y Windows: instalada junto a la estable y funcionando bien, según
    el usuario.
  - Pixel: instalada, pero no copió hosts ni sesiones, y el botón dice que la
    estable no está instalada. Causa probable: la estable del Pixel es la
    v1.2.0, sin `StableConfigProvider`. Sigue en
    [[Copia de la estable a la canary en Android con estables antiguas]].
- **Pendiente**:
  - ver que una canary instalada se actualiza sola a la siguiente build;
  - la copia en Android con la estable 1.3.0, la primera publicada con el
    proveedor (tarea de arriba).

## Resultado

- **Canal de build** `-PtitanChannel` → `BuildInfo.CHANNEL` y `AppChannel`.
  Los nombres de carpetas, el espacio de secretos, las carpetas del agente, la
  tarea del ayudante de escritorio y las sesiones de tmux/screen
  (`titan-canary-<id>`) salen de ahí.
- **Android**: `applicationId` `….canary`, etiqueta *titan-ssh canary* y la
  capa del icono de `src/canary/res`. `StableConfigProvider` está exportado
  solo en la estable, tras el permiso `signature` `READ_STABLE_CONFIG`, que
  declaran las dos apps.
- **Escritorio**: paquete `titan-ssh-canary`, MSI con su propio
  `upgradeUuid` en `Programs\titan-ssh-canary`, y `tar.gz`, `.desktop` e
  iconos propios. `install-linux.sh` toma el nombre de la carpeta del lanzador.
- **Actualizador**: `UpdateChannel`. La estable solo instala estables; la
  canary lee `releases/tags/canary`. El manifiesto firma el `channel`.
- **Agente**: carpetas `titan-ssh-canary` en el destino, y descarga de
  binarios del Release `canary`.
- **Copia de la estable**: `StableCopier` (al primer arranque y desde Ajustes).
  En escritorio lleva secretos; en Android no.
- **CI**: `release.yml` construye y publica la canary en cada push a `main`.
- **Iconos**: `RenderIcon.java` genera también los de la canary.
- **Documentación**: regla 4 del README,
  [[Pipeline de release en GitHub Actions]],
  [[Versionado único desde tag de git]] y [[BuildInfo]].
