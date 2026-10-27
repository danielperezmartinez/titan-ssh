---
Nombre: 'Probar en una candidata exactamente lo que se publica'
Estado: 'Pendiente'
Resumen: 'Poder probar en un dispositivo exactamente el commit que se va a publicar, y que a la estable solo llegue lo elegido. Propuesta: ramas release/X.Y de las que salen los tags, y una build candidata de cada una instalable aparte (una tercera app, otro canal de ADR-0027), sin que la canary de main baje nunca de versión. Se aborda cuando la canary y la estable estén igual, para empezar desde un punto limpio.'
Decisiones: 'El 2026-10-27 el usuario decide dejarla para más adelante, a empezar cuando main no tenga nada sin publicar respecto a la última versión estable (sin commits duplicados ni cambios de la canary sin probar). Recomendación del agente: opción 1 (tercera app candidata) con ramas release/X.Y; falta que el usuario elija y una ADR que amplíe o sustituya a ADR-0027.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T14:10:00+01:00
Última modificación: 2026-10-27T14:10:00+01:00
---

# Probar en una candidata exactamente lo que se publica

## Objetivo

El usuario quiere probar en sus dispositivos **solo lo que va a pasar a la
estable** y que a producción llegue únicamente eso. Hoy no se puede:

- La canary ([[ADR-0027 Canal canary instalable junto a la app estable]]) se
  construye desde `main` entera y solo desde `main`, y `release.yml` solo
  publica tags que apuntan a un commit de `main` (regla 4 del [[README]]).
- Publicar `main` lleva a la estable todo lo que hay en `main`. Lo que no está
  listo solo se puede ocultar limitándolo a la canary
  (`SessionType.experimental`, `AppChannel.isCanary`).

Surgió el 2026-10-27 al querer publicar una corrección sola cuando `main`
llevaba 62 commits sin publicar desde `v1.3.0` (escritorio remoto, instalación
de la clave pública, teclado del móvil…).

**Cuándo empezar**: cuando la canary y la estable estén igual (`main` sin
cambios sin publicar respecto al último tag), para montar el workflow y la ADR
desde un punto limpio, sin commits duplicados ni cambios de la canary sin
probar.

## Opciones estudiadas

### A. Una sola rama y el canal decide lo que se ve (lo que se hace hoy)

Todo en `main`; lo no listo se limita a la canary; se publica `main` con un
tag en el mismo commit probado en la canary.

- Sin cambios de proceso ni de workflow; la canary nunca baja de versión.
- Pero a la estable llega todo lo no limitado, aunque el usuario no lo haya
  usado en la canary (solo pasó la prueba de su tarea).
- Lo limitado se prueba en la canary **visible**; en la estable va oculto y ese
  caso solo lo cubren los tests. Si algo compartido cambió para esa función,
  la estable también lo lleva.
- Paliativo posible: antes de publicar, una build estable del mismo commit
  como artefacto de un `workflow_dispatch`, sin publicar nada.

### B. Rama de corrección desde el último tag, con la canary rodante

`hotfix/X.Y.x` desde `vX.Y.Z` con solo la corrección (también en `main`), la
canary lanzada a mano desde esa rama y el tag en ella.

- Exige que el workflow acepte tags y canary desde `hotfix/*` (el workflow
  que corre con un tag es el del commit etiquetado, así que el cambio va en
  las dos ramas) y cambiar la regla 4.
- **La canary baja de versión** mientras se prueba: pierde lo de `main`, el
  agente canary de los destinos también vuelve atrás, y una sesión de un tipo
  que la versión antigua no conoce (p. ej. escritorio remoto) hace que
  `JsonFileConfigStore` no cargue la configuración (`ConfigStoreException`)
  hasta volver a la canary de `main`.

### C. Rama `canary` de desarrollo y pasar a `main` solo lo elegido

Ya se descartó en ADR-0027 ("rama `develop` y PR a `main`"), aunque entonces
por otro motivo (solo añadía merges). Problemas para este objetivo:

- Lo publicado no es lo probado: en la canary cada cambio convive con todo lo
  demás; en `main` cae sobre otra base que nadie ha ejecutado.
- Las ramas divergen para siempre: cada cherry-pick es un commit nuevo, hay
  que traer `main` de vuelta a `canary` y los conflictos aparecen en lo que
  tocan los dos lados.
- Pasar una función entera (decenas de commits, algunos WIP) sin olvidar
  ninguno es frágil; con el límite por canal basta con `experimental = false`.
- Cambia el día a día: sesiones paralelas, worktrees y regla 4 dan por hecho
  `main`.

### D. Ramas `release/X.Y` y build candidata (propuesta)

- **`main`** sigue como hoy: desarrollo y canary en cada push.
- **`release/X.Y`**, una por versión menor. Para un arreglo (`1.3.1`),
  `release/1.3` nace de `v1.3.0` y recibe solo los commits elegidos (siempre
  primero en `main`). Para una menor (`1.4.0`), `release/1.4` se crea desde
  `main` con lo no terminado limitado a la canary.
- Cada push a `release/*` construye una **build candidata**: exactamente lo
  que se publicará. Si convence, el tag va **en ese mismo commit**.

Dónde se instala la candidata:

1. **Tercera app "titan-ssh candidata"** (recomendada). Otro canal de
   ADR-0027; como todo deriva del canal (nombres, carpetas, secretos, agente
   en los destinos, actualizador, icono), es casi mecánico. Nunca baja de
   versión, no toca la canary de `main` y copia la configuración de la
   estable igual que la canary. Inconvenientes: otra app en cada dispositivo,
   otra carpeta de agente en los destinos, más tiempo de CI (gratis en repo
   público) y en Android dar de alta las credenciales una vez más.
2. **Selector "main / candidata" en la canary.** Sin tercera app, pero cambiar
   es bajar de versión (mismos problemas que B), Android no instala un
   `versionCode` menor sin desinstalar y perder datos (cada cambio exigiría
   una build nueva), y mientras se prueba una no se tiene la otra. Frágil.
3. **La canary sale de `release/*` en vez de `main`.** Lo más sencillo, pero
   lo que está a medias en `main` deja de probarse en los dispositivos (p. ej.
   la comparación del escritorio remoto con RDP no habría sido posible).

Inconvenientes comunes de trabajar con ramas:

- Lo que entra en una `release/*` tiene que estar también en `main` (primero
  en `main`, luego cherry-pick). Un commit que dependa de otro no pasado da
  conflicto; la candidata lo descubre antes de publicar.
- Cambios de proceso: `release.yml` (candidata y tags desde `release/*`; la
  comprobación pasa de "está en `main`" a "está en la `release/*` de su
  versión"), una ADR que amplíe o sustituya ADR-0027 y la regla 4 del
  [[README]]. Las sesiones de agentes siguen trabajando en `main`.
- No se mantienen versiones antiguas: al salir `1.4.0`, `release/1.3` deja de
  usarse.

## Criterios de finalización

- El usuario elige la opción; ADR aceptada (amplía o sustituye a ADR-0027).
- `release.yml` construye la candidata desde `release/*` y publica tags desde
  ella; regla 4 del [[README]] y [[Pipeline de release en GitHub Actions]]
  actualizadas.
- Probado con una versión real: candidata instalada en los dispositivos,
  probada por el usuario y publicada con el tag en el mismo commit.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
