---
Nombre: 'Retirar el preámbulo anterior del agente'
Estado: 'Hecha'
Resumen: 'Quitar del agente el handshake anterior del punto de encuentro (marcas TTNAGNT1, TTNACTL1, TTNADSK1 y TTNADCT1), que ADR-0018 conserva solo durante la transición. Va, como muy tarde, en 0.1.0-beta.14: el build raíz se niega a generar beta.14 o posterior mientras rendezvous.go contenga legacyPreambleMagic.'
Decisiones: 'Acordado con el usuario el 2026-10-02: la versión que publica ADR-0018 (prevista 0.1.0-beta.11) conserva el preámbulo anterior para la transición; la siguiente, o como mucho la otra, lo quita del todo. El recordatorio es doble: esta tarea en el seguimiento y la comprobación del build raíz (legacyHandshakeLastVersionCode). El 2026-10-03 el usuario amplió el plazo una beta (último con el preámbulo: 0.1.0-beta.13): en Android 16, beta.11 y beta.12 no conectaban con hosts ed25519 ([[Claves ed25519 en Android 16 con Conscrypt]]), así que no pudo actualizar los agentes desde ellas y se habrían quedado huérfanos.'
Bloqueada: []
Fecha de creación: 2026-10-02T08:53:55+02:00
Última modificación: 2026-10-07T11:46:02+02:00
---

# Retirar el preámbulo anterior del agente

## Objetivo

[[ADR-0018 Autenticación mutua en el punto de encuentro del agente]] cambia
el handshake entre el front y el daemon (y el ayudante de escritorio), pero
deja el anterior durante una o dos versiones para que la actualización no se
atasque:

- El daemon y el ayudante lo siguen aceptando, para las apps sin actualizar.
- La CLI lo usa para reconocer y detener un agente de una versión anterior
  (`errLegacy`, `stopLegacyDaemon`, `stopLegacyDesktop`).

Pasadas esas versiones ya no hace falta y solo es código y superficie de más.
Esta tarea lo quita.

**No se puede olvidar**: el `build.gradle.kts` raíz falla con cualquier
versión posterior a `0.1.0-beta.13` (`legacyHandshakeLastVersionCode`) si
`agent/cmd/titan-agent/rendezvous.go` contiene todavía `legacyPreambleMagic`.

## Criterios de finalización

- En `agent/cmd/titan-agent/`:
  - fuera las constantes `legacy*`, `dialLegacy`, `legacyAllowed`,
    `answersLegacy`, `stopLegacyDaemon` y `stopLegacyDesktop`;
  - `acceptMagics` ya no recibe marcas antiguas;
  - `request` deja de devolver `errLegacy`.
- Un agente anterior que siga en marcha se trata como uno que no responde
  (`unreachable`). Se decide qué texto ve el usuario en el panel y en
  `E_AGENT_OUTDATED`: por ejemplo, que lo cierre a mano o que reinicie la
  sesión del destino.
- Fuera los tests del handshake anterior (`fakeOlderDaemon`,
  `TestInputFrontReplacesAnOlderHelper` y los casos con marcas antiguas). Se
  mantiene la comprobación de que un oyente cualquiera no recibe el token.
- Fuera la comprobación de `legacyHandshakeLastVersionCode` del build raíz.
- Se anota en ADR-0018 (nota al principio) en qué versión se retiró.
- La app se prueba en el emulador contra el contenedor de pruebas, después de
  actualizar desde la versión que conserva el preámbulo anterior.

## Avance (2026-10-07)

Hecho en el código, para la `0.1.0-beta.14`:

- Fuera las constantes `legacy*`, `dialLegacy`, `legacyAllowed`,
  `answersLegacy`, `errLegacy`, `stopLegacyDaemon`, `stopLegacyDesktop` y el
  estado `legacy` del agente; `acceptMagics` solo recibe las marcas actuales.
- Un daemon que no completa el apretón aparece como `unreachable`. `--stop`
  ya no lo mata: falla diciendo su versión y su PID y que se cierre a mano o
  se reinicie la máquina. `--status` legible dice lo mismo. El front da
  `E_AGENT_OUTDATED` con el PID, y la app explica que se cierre a mano o se
  reinicie el destino (el panel ya decía eso para un agente que no responde,
  y no ofrece "Actualizar" en ese estado). La app conserva el estado
  `legacy` al leer, pero su propia CLI ya no lo envía.
- Un ayudante de escritorio antiguo que no responde ni a una parada: el
  front falla enseguida con `E_NO_DESKTOP`, su versión y su PID, en vez de
  esperar a un escritorio. Uno de `0.1.0-beta.11` a `beta.13`, que no conoce
  `--desktop-run`, se para por el canal de control y se sustituye.
- Tests: los del apretón antiguo se sustituyen por otros que comprueban que
  un agente o un ayudante que solo lo habla no recibe nunca el token
  (`TestAnOlderDaemonIsUnreachable`,
  `TestInputFrontReportsAnOlderHelperThatDoesNotAnswer`,
  `TestDesktopRunReplacesAHelperWithoutIt` y el caso del token en crudo en
  `TestDaemonClosesUnauthenticatedConnections`).
- Fuera la comprobación del build raíz; nota en ADR-0018.
## Verificación

- `go test ./...` en Windows y `go test -race ./...` en Linux (Docker).
- `:shared:desktopTest`: 332 tests y solo falla `DesktopSecretStoreTest`, por
  el entorno: se ejecutó desde una sesión SSH con clave, sin Administrador de
  credenciales de Windows (error 1312). Compilan `:shared:compileAndroidMain`,
  `:desktopApp:compileKotlin` y `:androidApp:assembleDebug`.
- `printVersion -PtitanVersion=0.1.0-beta.14` ya no falla.
- CLI nueva contra un daemon `0.1.0-beta.13` compilado desde su tag, en el
  contenedor de pruebas: `--status --json` lo lee (`running`, versión
  `beta.13`) y `--stop` lo para.
- Emulador (build de debug) contra el contenedor con ese daemon `beta.13`:
  la pestaña conecta con nivel 3; el panel muestra "Agente de otra versión:
  0.1.0-beta.13" y "Actualizar" lo para; la siguiente conexión arranca el
  agente nuevo con su sesión.

## Resultado

El agente ya no habla el apretón de manos que enviaba el token: ni lo acepta
ni lo usa. Lo que quede de `0.1.0-beta.10` o anterior se ve como un agente
que no responde, con su PID, y se cierra a mano. Sale en `0.1.0-beta.14`.
