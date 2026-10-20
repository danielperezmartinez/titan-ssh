---
Nombre: 'Copia de la estable a la canary en Android con estables antiguas'
Estado: 'En curso'
Resumen: 'En el Pixel del usuario, la canary 1.2.1-canary.27 no copió los hosts ni las sesiones de la estable, ni al arrancar ni con el botón Copiar de la app estable, que dice que la estable no está instalada aunque sí lo está. Causa: la estable instalada es la v1.2.0, anterior al StableConfigProvider, así que la canary no encuentra el proveedor y lo toma por app ausente (confirmado en el emulador). El mensaje ya distingue estable ausente, anterior al proveedor, firmada con otra clave y sin configuración, y está probado en el emulador con una estable v1.2.0. Publicada la estable 1.3.0 con el proveedor el 2026-10-20; falta comprobar la copia en el Pixel cuando le llegue.'
Decisiones: 'El 2026-10-20 el usuario acepta corregir el mensaje y publicar una estable con el proveedor, y pide hacerlo en una sesión nueva.'
Bloqueada: []
Versión: '1.3.0'
Fecha de creación: 2026-10-20T20:30:00+02:00
Última modificación: 2026-10-20T22:00:00+02:00
---

# Copia de la estable a la canary en Android con estables antiguas

## Objetivo

Que la canary de Android pueda copiar la configuración de la estable
([[ADR-0027 Canal canary instalable junto a la app estable]] §4), y que, si no
puede, diga el motivo real.

Lo que vio el usuario el 2026-10-20 en su Pixel, con la canary
`1.2.1-canary.27` instalada desde el Release `canary`:

- en el primer arranque no se copiaron hosts ni sesiones;
- el botón *Copiar de la app estable* (Configuración → Ajustes) responde «la
  app estable no está instalada o aún no tiene configuración», aunque la
  estable está instalada y tiene configuración.

En Linux y en Windows la canary funciona, copia incluida: allí lee la carpeta
de la estable y no necesita proveedor.

## Causa (confirmada en el emulador)

En Android, la canary pide la configuración al `StableConfigProvider` de la
estable (`shared/src/androidMain/.../config/StableCopy.android.kt`). Ese
proveedor entró en el commit `2e115ec`, posterior al tag `v1.2.0`. La estable
del Pixel es la v1.2.0, así que no lo tiene. En `readStableSnapshot`, una
autoridad desconocida devuelve `null` o lanza `IllegalArgumentException`, y
ambos casos acaban en `NotCopied` con el mensaje de app ausente.

En el emulador funcionó porque las dos apps eran builds de debug del mismo
código, con el proveedor.

Para confirmarlo, en el Pixel o en el emulador con una estable v1.2.0:
`adb shell dumpsys package io.github.danielperezmartinez.titanssh | grep -i
stableconfig` no debería mostrar ningún proveedor.

## Alcance

1. **Mensaje**: distinguir estable ausente de estable sin proveedor.
   - Que la canary pueda ver el paquete de la estable: hoy solo declara
     `<queries><provider>`. Añadir `<queries><package>` con el id de la estable
     (marcador de manifiesto, con el sufijo de worktree en debug, como
     `stableConfigAuthority`).
   - Si el paquete existe pero `resolveContentProvider` no encuentra la
     autoridad: «la app estable instalada es anterior a la versión que comparte
     la configuración; actualízala y vuelve a intentarlo».
   - Si existe, tiene proveedor y no tiene configuración: decirlo así.
2. **Probarlo** en el emulador con la estable v1.2.0 del Release (o una build
   sin proveedor) junto a la canary de debug. Después, en la canary real tras
   el push.
3. **Publicar una versión estable** con el proveedor, siguiendo la regla 4 del
   [[README]]. Propuesta: `1.3.0`, porque cambia el comportamiento del
   actualizador (la estable deja de instalar pre-releases) y añade el
   proveedor. El usuario confirma el número.
4. Con la estable nueva en el Pixel, comprobar que *Copiar de la app estable*
   trae los hosts y las sesiones (sin credenciales, como decide la ADR).

## Criterios de finalización

- Con una estable sin proveedor, la canary explica que hay que actualizarla.
  Con la estable ausente, dice que no está instalada.
- Con la estable publicada nueva, la canary del Pixel copia hosts y sesiones.
- Se rellena la verificación pendiente de
  [[Canal canary instalable junto a la app estable]] para Android.

## Verificación

2026-10-20, rama `worktree-canary-old-stable`:

- **Tests**: los de escritorio pasan. `FileStableCopierTest` comprueba ahora
  que el motivo llega tal cual al resultado.
- **Emulador** (canary de debug junto a la estable de debug del mismo
  worktree; capturas enseñadas al usuario):
  1. sin estable: «la app estable no está instalada»;
  2. con una estable construida desde el tag `v1.2.0`: «la app estable
     instalada (1.2.0) es anterior a la versión que comparte la
     configuración; actualízala y vuelve a intentarlo». Es lo que pasaba en
     el Pixel, y confirma la causa;
  3. estable nueva recién instalada: «la app estable aún no tiene
     configuración»;
  4. estable v1.2.0 con hosts actualizada encima a la nueva, conservando sus
     datos (lo que hará el Pixel con la 1.3.0): la canary copia 1 host y
     1 sesión, también sola en el primer arranque tras `pm clear`.
- No probado en dispositivo: el caso de firma distinta (`SecurityException`
  del proveedor). Solo se da con una build de debug al lado de una publicada.
- **Publicada la 1.3.0** (2026-10-20, run `39484979343`, todo en verde):
  Release `v1.3.0` con sus assets, no pre-release.
- **Pendiente**: con la 1.3.0 en el Pixel, *Copiar de la app estable* en la
  canary (punto 4 del alcance).

## Resultado

- `readStable` devuelve `StableRead`: `StableSnapshot` o
  `StableUnavailable(reason)`, y el motivo sale en *Copiar de la app
  estable*.
- Android: la canary conoce el paquete de la estable (marcador
  `stableAppId`, que sustituye a `stableConfigAuthority`; la autoridad es
  `<stableAppId>.stableconfig`) y lo declara en `<queries><package>`. Con eso
  distingue paquete ausente, paquete sin proveedor (indica su
  `versionName`), firma distinta y estable sin configuración.
- Escritorio: distingue carpeta de la estable ausente (no instalada o nunca
  abierta) de carpeta sin `config.json`.
