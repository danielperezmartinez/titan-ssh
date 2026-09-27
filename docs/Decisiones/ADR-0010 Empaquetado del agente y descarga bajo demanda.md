---
Nombre: 'Empaquetado del agente y descarga bajo demanda'
Número: 10
Estado: 'Aceptada'
Resumen: 'Cómo llegan los binarios de titan-agent al cliente cuando ADR-0009 amplía los destinos a 13 (unos 3,5 MB cada uno, unos 46 MB si se empaquetaran todos en la APK y en el paquete de escritorio). Decisión del usuario (2026-09-23): empaquetar en la app los destinos principales (linux amd64/arm64, darwin amd64/arm64, windows amd64/arm64) y descargar el resto bajo demanda, solo cuando una sesión lo necesite, verificando cada binario descargado contra un SHA-256 fijado dentro de la propia app. Origen de la descarga (decidido el 2026-09-27): los assets del GitHub Release de cada versión, que publica los 13 binarios.'
Decisión: 'Empaquetar los seis destinos principales como recursos de la app y obtener el resto bajo demanda de los assets del GitHub Release de la misma versión (releases/download/v<versión>/titan-agent-<versión>-<os>-<arch>[.exe]), verificando cada binario contra un SHA-256 fijado en la app en tiempo de compilación (/agent/SHA256SUMS); si falla la descarga o el checksum, se degrada al nivel 2/1. Nunca se ejecuta un binario cuyo checksum no coincida. El pipeline compila los binarios una sola vez y los usa para todos los paquetes y para el Release.'
Consecuencias: 'La APK y el paquete de escritorio solo crecen con seis binarios (la APK de release pasa de 8,5 a 14 MB). A cambio, los destinos poco comunes necesitan red saliente desde el cliente la primera vez, con su superficie de privacidad y disponibilidad (GitHub puede estar caído o bloqueado), y cada versión publica sus binarios en el Release. Una build de desarrollo no tiene Release, así que ahí esos destinos degradan.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-23T22:30:00+02:00
Última modificación: 2026-09-27T10:40:00+02:00
---

# ADR-0010 · Empaquetado del agente y descarga bajo demanda

> **Estado: Aceptada** (2026-09-27). El reparto (empaquetar lo principal y
> descargar el resto) lo decidió el usuario el 2026-09-23, y el origen de la
> descarga (punto 3), el 2026-09-27. Implementada en
> [[Instalación del agente en destinos Windows y multi-SO]].

## Contexto

[[ADR-0009 Agente de nivel 3 portable a todos los destinos]] amplía los destinos
compilados del agente a 13:
- `linux/{amd64, arm64, arm, 386, riscv64, ppc64le, s390x}`;
- `darwin/{amd64, arm64}`;
- `windows/{amd64, arm64}`;
- `freebsd/{amd64, arm64}`.

Hoy los tres binarios existentes se empaquetan como recursos JVM (`/agent/`,
task `buildAgentBinaries`) y viajan dentro de la APK y del paquete de
escritorio (ver [[titan-agent distribución multi-arch e instalación]]). Con unos
2,5 MB por binario, empaquetar los 13 añadiría unos 33 MB (en la
implementación, unos 3,5 MB por binario: unos 46 MB).

## Decisión

### 1. Destinos empaquetados en la app

`linux/amd64`, `linux/arm64`, `darwin/amd64`, `darwin/arm64`, `windows/amd64` y
`windows/arm64`. Se empaquetan como hoy (recursos `/agent/`, cargados con
`AgentBinaries.bundled`), junto con el manifiesto `/agent/SHA256SUMS`, que fija
el SHA-256 de los 13.

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

### 3. Origen de la descarga: GitHub Releases

Decidido por el usuario el 2026-09-27, al empezar
[[Instalación del agente en destinos Windows y multi-SO]]:

- Los binarios salen de los **assets del GitHub Release** de la misma versión:
  `https://github.com/danielperezmartinez/titan-ssh/releases/download/v<versión>/titan-agent-<versión>-<os>-<arch>[.exe]`.
  El repositorio es público, así que no hacen falta credenciales, y el
  [[Pipeline de release en GitHub Actions]] ya publica cada versión.
- Cada Release lleva los **13** binarios, también los seis empaquetados, para
  que sirvan para instalar el agente a mano. `SHA256SUMS` los cubre.
- El pipeline compila los binarios **una sola vez** (job `agent`), y todos los
  paquetes y el Release usan esos mismos ficheros. Así, el SHA-256 fijado en
  cada app es el del asset publicado.
- Una build de desarrollo (`0.0.0-dev`) no tiene Release: en ella solo tienen
  nivel 3 los destinos empaquetados.

Opciones descartadas: un almacenamiento propio (bucket o servidor estático),
que cuesta dinero o mantenimiento, y otro registro de artefactos, que no
aporta nada sobre el Release.

## Alternativas consideradas

- **Empaquetar todos los destinos**: sin red saliente, pero unos 46 MB más en la
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
