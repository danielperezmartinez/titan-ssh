---
Nombre: 'Retirar el preámbulo anterior del agente'
Estado: 'Pendiente'
Resumen: 'Quitar del agente el handshake anterior del punto de encuentro (marcas TTNAGNT1, TTNACTL1, TTNADSK1 y TTNADCT1), que ADR-0018 conserva solo durante la transición. Va en 0.1.0-beta.12 y, como muy tarde, en 0.1.0-beta.13: el build raíz se niega a generar beta.13 o posterior mientras rendezvous.go contenga legacyPreambleMagic.'
Decisiones: 'Acordado con el usuario el 2026-10-02: la versión que publica ADR-0018 (prevista 0.1.0-beta.11) conserva el preámbulo anterior para la transición; la siguiente, o como mucho la otra, lo quita del todo. El recordatorio es doble: esta tarea en el seguimiento y la comprobación del build raíz (legacyHandshakeLastVersionCode = 0.1.0-beta.12).'
Bloqueada: []
Fecha de creación: 2026-10-02T08:53:55+02:00
Última modificación: 2026-10-02T08:53:55+02:00
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
versión posterior a `0.1.0-beta.12` (`legacyHandshakeLastVersionCode`) si
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

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
