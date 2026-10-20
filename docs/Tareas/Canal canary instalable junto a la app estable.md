---
Nombre: 'Canal canary instalable junto a la app estable'
Estado: 'En curso'
Resumen: 'Crear la variante canary de la app según ADR-0027. Es otra app que se instala junto a la estable en Android, Windows y Linux. CI la construye en cada push a main y la publica en el Release rodante canary, y se actualiza sola desde él. Copia de la estable hosts y configuración, con secretos solo en escritorio. Tiene sus propias carpetas, su espacio de secretos y su agente en los destinos. Incluye que el actualizador de la estable deje de instalar pre-releases.'
Decisiones: 'El 2026-10-20 el usuario elige la opción de una app canary aparte, frente a una rama develop con PR o un canal beta en Ajustes, y pide compartir los hosts si no supone un problema de seguridad. Decisión en [[ADR-0027 Canal canary instalable junto a la app estable]], aceptada el 2026-10-20. Icono: el de la estable con la parte azul de la T en el naranja de la app. En Android no se pasan secretos a la canary (lo que interesaba eran las claves hardware, que no se pueden).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-20T10:40:00+02:00
Última modificación: 2026-10-20T11:05:00+02:00
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

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
