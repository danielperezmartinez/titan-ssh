---
Nombre: 'Mouse pad fluido sin tirones'
Estado: 'Hecha'
Resumen: 'El puntero del mouse pad avanzaba a pequeños trompicones. Medido con pktmon en el destino mientras el usuario lo usaba desde el Pixel: las tramas de movimiento (una cada ~8 ms) llegaban en ráfagas de 5-7 cada ~47 ms (el RTT de Tailscale) y a ratos 13-18 tras parones de 100-200 ms. Causa: sshj deja Nagle activo en el socket SSH, que retiene cada paquete pequeño hasta el ACK del anterior. Arreglo: SshjConnector abre el socket con TCP_NODELAY (también acelera las teclas de la terminal). Publicado en v0.1.0-beta.15: la misma captura da una trama por segmento cada ~8 ms (p99 45 ms frente a 221 ms) y el usuario confirma en el Pixel que va muy bien.'
Decisiones: 'TCP_NODELAY en todos los sockets SSH, como hace OpenSSH en las sesiones interactivas. No había ninguna decisión ni aviso privado detrás de Nagle (revisado en el historial, la bóveda y los avisos); no cambia el cifrado ni lo que viaja, solo cuándo sale cada paquete.'
Bloqueada: []
Fecha de creación: 2026-10-07T21:05:00+02:00
Última modificación: 2026-10-08T00:10:00+02:00
---

# Mouse pad fluido sin tirones

## Objetivo

El usuario usa el mouse pad desde el Pixel contra un destino Windows y el
puntero no se mueve fino: va a pequeños trompicones. Saber por qué, con una
medida como la de [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]],
y arreglarlo.

## Criterios de finalización

- Se mide el ritmo al que llegan al destino las tramas de movimiento y se
  identifica la causa de los tirones.
- Con el arreglo, las tramas llegan al destino al ritmo con que el móvil las
  genera, sin ráfagas, medido con la misma prueba.
- El usuario lo prueba en el Pixel y nota el puntero fluido.

## Análisis

Camino de un movimiento: `MousepadView` (un `PointerMove` por evento de
Compose, unos 8 ms a 120 Hz) → `SessionTab.sendInput` → `InputTransport.send`
→ `SshjExecChannel.send` (una escritura y un `flush`, un paquete SSH de pocas
decenas de bytes por trama) → socket TCP → `sshd` del destino → `titan-agent
--input` → TCP de loopback → ayudante de escritorio → `SendInput`.

- sshj 0.41.1 nunca llama a `setTcpNoDelay` y `SshjConnector` no le da un
  socket propio: el socket SSH tiene **Nagle activo**. Nagle solo deja un
  segmento pequeño sin confirmar en vuelo; los siguientes esperan al ACK.
  El destino no contesta nada al mouse pad, así que su ACK no viaja con datos
  y puede ir diferido.
- El tramo del agente usa TCP de Go, que activa `TCP_NODELAY` por defecto.

## Verificación

- **Cadena del destino sin red** (2026-10-07): un test desechable manda tramas
  `PointerMove` reales a 120 Hz con sshj al `sshd` de Windows por loopback y un
  receptor apunta cuándo llega cada una. 400 de 400 tramas, una por lectura,
  retraso p99 < 1,5 ms con Nagle y sin él. La cadena sshj → Win32-OpenSSH →
  stdin del proceso no retrasa nada; en loopback el ACK es inmediato y Nagle
  no llega a actuar, así que falta medir con el RTT de una red real.
- **Captura real desde el Pixel** (2026-10-07, versión publicada, sin el
  arreglo): `pktmon` en el destino Windows, filtrado al SSH que llega del Pixel
  por Tailscale (RTT del momento 28-63 ms), mientras el usuario movía el dedo
  en círculos. Cada trama de movimiento ocupa un paquete SSH de 68 B. Mientras
  se mueve, llega **un segmento TCP cada ~47 ms** (el RTT) con 5-7 tramas
  dentro (340/408/476 B) y, a ratos, huecos de 100-150 ms con 13-18 tramas de
  golpe (884-1240 B). El móvil genera una trama cada ~8 ms; el destino las
  recibe en ráfagas a ~20 Hz con parones. **Causa confirmada: Nagle.**
- Arreglo: `NoDelaySocketFactory` en `SshjConnector` (sshj crea siempre el
  socket con `createSocket()` y luego lo conecta). Test headless
  `SshjConnectorTest.sockets_turn_nagle_off`; `:shared:desktopTest` del
  paquete `ssh` y `:shared:compileAndroidMain` en verde. Suite completa
  `:shared:desktopTest`: 332 de 333; el único fallo es
  `DesktopSecretStoreTest` (error 1312), ambiental al ejecutarse en la sesión 0
  de Windows. `:androidApp:assembleDebug` y `:desktopApp:compileKotlin` en
  verde.
- **Publicado en `v0.1.0-beta.15`** (2026-10-07): workflow `Release` y CI de
  `main` en verde, pre-release, `sha256sum -c SHA256SUMS` sin errores (19 de
  19) y APK firmada con la clave de release (huella del `README.md`). El push
  se hizo desde la sesión 0 con `titan-agent --desktop-run` (ADR-0019), que
  lanzó `git push` en la sesión del usuario.
- **Captura real con la beta.15** (2026-10-08, misma prueba: círculos con el
  mouse pad desde el Pixel, `pktmon` en el destino). Tramos de movimiento de
  la conexión del mouse pad, antes → después:

  | | beta.14 | beta.15 |
  |---|---|---|
  | Tramas por segmento TCP | 6,2 | 1,0 |
  | Hueco entre segmentos p50 | 50 ms | 8 ms |
  | Hueco p90 | 111 ms | 11 ms |
  | Hueco p99 | 221 ms | 45 ms |
  | Segmentos con hueco > 20 ms | 95 % | 3 % |

  Cada movimiento sale ya en su propio paquete al ritmo con que el móvil lo
  genera (~8 ms). Los pocos huecos largos que quedan son de la red (Wi-Fi) o
  de pausas del dedo, no de ráfagas.
- **Seguridad**: no había ninguna decisión detrás de Nagle (ni en el historial,
  ni en la bóveda, ni en los avisos privados, revisados por el usuario); era el
  valor por defecto de Java, que sshj no cambia. `TCP_NODELAY` solo decide
  cuándo sale un paquete ya cifrado, y OpenSSH lo activa en toda sesión
  interactiva. Nagle tampoco ocultaba el ritmo de las pulsaciones: tecleando
  más despacio que el RTT, cada tecla ya salía en su propio paquete.

## Resultado

- `SshjConnector`: `NoDelaySocketFactory` abre el socket de cada conexión
  SSH con `TCP_NODELAY` (sshj lo crea con `createSocket()` y luego lo
  conecta); por ProxyJump, el primer salto es el único con socket. Test
  `SshjConnectorTest.sockets_turn_nagle_off`.
- Publicado en `v0.1.0-beta.15`. Medido con la misma captura antes y después
  (tabla en **Verificación**), y el usuario confirma el 2026-10-08 en el Pixel
  que el puntero va muy bien.
- De paso, las teclas de la terminal tampoco esperan ya al ACK de la anterior.
