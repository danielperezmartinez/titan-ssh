---
Nombre: 'Empaquetado del agente y descarga bajo demanda'
Número: 10
Estado: 'Propuesta'
Resumen: 'Cómo llegan los binarios de titan-agent al cliente cuando ADR-0009 amplía los destinos a unos 15 (unos 2,5 MB cada uno, unos 37 MB si se empaquetaran todos en la APK y en el paquete de escritorio). Decisión del usuario (2026-09-23): empaquetar en la app los destinos principales (linux amd64/arm64, darwin amd64/arm64, windows amd64/arm64) y descargar el resto bajo demanda, solo cuando una sesión lo necesite, verificando cada binario descargado contra un SHA-256 fijado dentro de la propia app. PENDIENTE: el origen de la descarga (p. ej. releases de GitHub, que exigen repositorio público o credenciales).'
Decisión: 'Empaquetar los seis destinos principales como recursos de la app (como hoy con buildAgentBinaries) y obtener el resto bajo demanda desde un origen por decidir, verificando cada binario contra un SHA-256 fijado en la app en tiempo de compilación; si falla la descarga o el checksum, se degrada al nivel 2/1 con diagnóstico. Nunca se ejecuta un binario cuyo checksum no coincida.'
Consecuencias: 'La APK y el paquete de escritorio solo crecen con seis binarios. A cambio, los destinos poco comunes necesitan red saliente desde el cliente la primera vez, con su superficie de privacidad y disponibilidad (el origen puede estar caído o bloqueado), y cada versión del agente obliga a publicar los binarios en ese origen y a fijar sus checksums en la app.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-23T22:30:00+02:00
Última modificación: 2026-09-23T22:30:00+02:00
---

# ADR-0010 · Empaquetado del agente y descarga bajo demanda

> **Estado: Propuesta.** El reparto (empaquetar lo principal y descargar el
> resto) lo decidió el usuario el 2026-09-23. Queda pendiente el **origen de la
> descarga** (punto 3). Mientras sea `Propuesta`, esta ADR se completa
> editándola.

## Contexto

[[ADR-0009 Agente de nivel 3 portable a todos los destinos]] amplía los destinos
compilados del agente a unos 15:
- `linux/{amd64, arm64, arm, 386, riscv64, ppc64le, s390x}`;
- `darwin/{amd64, arm64}`;
- `windows/{amd64, arm64}`;
- `freebsd/{amd64, arm64}`.

Hoy los tres binarios existentes se empaquetan como recursos JVM (`/agent/`,
task `buildAgentBinaries`) y viajan dentro de la APK y del paquete de
escritorio (ver [[titan-agent distribución multi-arch e instalación]]). Con unos
2,5 MB por binario, empaquetar los 15 añadiría unos 37 MB.

## Decisión

### 1. Destinos empaquetados en la app

`linux/amd64`, `linux/arm64`, `darwin/amd64`, `darwin/arm64`, `windows/amd64` y
`windows/arm64`. Se empaquetan como hoy (recursos `/agent/`, cargados con
`AgentBinaries.load`).

### 2. Resto de destinos: descarga bajo demanda

- Solo cuando una sesión con nivel 3 detecta un destino no empaquetado.
- **Integridad**: el SHA-256 de cada binario descargable se fija **dentro de la
  app** en tiempo de compilación (generado por el mismo build que compila los
  binarios). El binario descargado se verifica contra ese valor antes de subirlo
  al destino; si no coincide, se descarta. Nunca se confía en un checksum que
  venga del propio origen de descarga.
- **Caché** local en el cliente por versión, para no descargar en cada sesión.
- **Fallo** de red o de checksum → degradar al nivel 2/1 con diagnóstico
  (código de cliente en
  [[Diagnóstico cuando el nivel 3 no está disponible]]).
- **Privacidad**: la descarga solo revela al origen la versión del agente y la
  arquitectura; no se envía nada del destino ni del usuario.

### 3. Origen de la descarga — PENDIENTE

Opciones a valorar:
- **Releases de GitHub** del repositorio. Solo funciona sin credenciales si el
  repositorio es **público**.
- Un almacenamiento propio (bucket o servidor estático).
- Otro registro de artefactos.

Criterios: disponible sin credenciales para el usuario final, estable, y que la
publicación de cada versión del agente pueda automatizarse.

## Alternativas consideradas

- **Empaquetar todos los destinos**: sin red saliente, pero unos 37 MB más en la
  APK y en el paquete de escritorio. Descartada por el tamaño.
- **Empaquetar solo los principales, sin descarga**: los destinos poco comunes
  quedarían sin nivel 3. Descartada: contradice el objetivo de ADR-0009.
- **Descargar desde el propio destino** (que el host descargue el binario): exige
  red saliente y herramientas (`curl`/`wget`) en el destino, que puede no tener.
  Descartada.

## Consecuencias

- **Positivas**: la app solo crece con seis binarios; los destinos poco comunes
  siguen teniendo nivel 3; la integridad no depende del origen de descarga.
- **Negativas / compromisos**: red saliente desde el cliente la primera vez
  para esos destinos; cada versión del agente obliga a publicar sus binarios y a
  fijar sus checksums en la app; si el origen no está disponible, esos destinos
  degradan.

Ver [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y
[[Instalación del agente en destinos Windows y multi-SO]].
