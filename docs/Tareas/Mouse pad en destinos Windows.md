---
Nombre: 'Mouse pad en destinos Windows'
Estado: 'En curso'
Resumen: 'Subtarea 1 del mouse pad: controlar ratón y teclado de un destino Windows desde el móvil, y construir la base común (ADR, tipo de sesión, protocolo de entrada y pestaña de gestos). El agente que lanza sshd corre en la sesión 0, sin escritorio, así que SendInput desde ahí no llega a la pantalla del usuario. Para entrar en su escritorio sin ser administrador, el agente registra una tarea programada del usuario, "solo cuando haya iniciado sesión", y la lanza; arranca titan-agent en modo ayudante dentro de la sesión interactiva, que recibe los eventos del agente por loopback con token y los aplica con SendInput. La tarea se ve y se quita desde el panel del agente.'
Decisiones: 'Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. El usuario aceptó el 2026-10-01 la tarea programada visible y gestionable desde el panel del agente, frente a una entrada de arranque automático (siempre activa) o un servicio (exige administrador). Experimento del 2026-10-01: la tarea programada solo interactiva, creada y lanzada por SSH, arranca en la sesión del usuario (también de escritorio remoto) y mueve el cursor. El diseño es [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]], aceptada por el usuario el 2026-10-01: cerrar la pestaña no para el ayudante (solo cerrar la sesión de Windows o quitarlo desde el panel), y el binario de Windows pasa al subsistema gráfico. El desbloqueo de la pantalla de bloqueo queda aparcado en [[Desbloqueo del destino Windows con un servicio de sistema]].'
Bloqueada: []
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-01T19:45:00+02:00
---

# Mouse pad en destinos Windows

Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. Va
primero: además del caso Windows, construye la base común que reutilizan
[[Mouse pad en destinos Linux X11]] y [[Mouse pad en destinos Linux Wayland]].

## Objetivo

Que una sesión mouse pad contra un destino Windows mueva el ratón, haga clic,
arrastre, haga scroll y escriba en el escritorio del usuario.

## Por qué hace falta un ayudante

Win32-OpenSSH lanza los procesos de la sesión SSH, y con ellos el daemon del
agente, en la **sesión 0**, que no tiene escritorio (ver
[[Transparencia y control del agente en el destino]]). `SendInput` solo
actúa sobre el escritorio de la sesión desde la que se llama, así que hace
falta un proceso dentro de la sesión interactiva del usuario.

## Propuesta

1. **ADR** del mouse pad: tipo de sesión, protocolo de entrada, ayudante de
   escritorio, seguridad y si el tipo se ofrece en escritorio. Se escribe y se
   acepta antes de tocar código. Propuesta en
   [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]], que es
   la fuente de verdad del diseño; los puntos siguientes son el resumen
   inicial.
2. **Ayudante por tarea programada**: el agente registra una tarea del propio
   usuario con inicio de sesión interactivo ("ejecutar solo cuando el usuario
   haya iniciado sesión", sin guardar contraseña) y la lanza. La tarea arranca
   `titan-agent` en un modo ayudante (por ejemplo `--desktop`) en la sesión
   interactiva. El ayudante y el daemon se encuentran por TCP en `127.0.0.1`
   con token, como en
   [[titan-agent punto de encuentro TCP loopback con token]].
3. **Inyección** con `SendInput` de `user32.dll`, cargada con
   `golang.org/x/sys/windows` (sin cgo ni dependencias nuevas): movimiento
   relativo, botones, rueda vertical y horizontal, texto con
   `KEYEVENTF_UNICODE` (acentos y ñ sin depender de la distribución del
   teclado) y teclas virtuales para las especiales y las combinaciones.
4. **Base común**: tipo de sesión en el modelo con migración, tramas de
   entrada en `protocol.go` y `AgentProtocol.kt` (en sincronía), y la pestaña
   de gestos y teclado.
5. **Transparencia**: el panel del agente muestra la tarea programada y si el
   ayudante está corriendo, y permite quitarla. `--stop` también para el
   ayudante.

## Experimento (2026-10-01)

Sonda desechable en Go (fuera del repositorio) que registra su sesión, la
sesión de consola, la ventana en primer plano y si `SendInput` mueve el
cursor. Ejecutada contra el sshd del PC de pruebas (Windows 10 22H2,
Win32-OpenSSH 10.0p2) con el usuario conectado al PC por escritorio remoto.

| Prueba | Resultado |
|---|---|
| Sonda lanzada directamente por SSH | Sesión 0. `GetCursorPos` falla ("requiere una estación de ventana interactiva") y `SendInput` devuelve 0. ❌ |
| Tarea creada por SSH con `schtasks /create ... /sc once /it` y lanzada con `schtasks /run` | Modo "Solo interactivo". Arranca en la sesión de escritorio remoto del usuario (no en la de consola) y mueve el cursor. ✅ |
| La misma tarea creada desde XML (`schtasks /xml`) | ✅, sin las restricciones de batería ni el límite de 72 h que pone `schtasks` por defecto. El XML tiene que ir en UTF-16: en UTF-8 da "no se pudo cambiar la codificación". |
| Binario con el subsistema gráfico (`-H windowsgui`) con stdin y stdout redirigidos dentro de la sesión SSH | Lee, escribe y devuelve su código de salida. ✅ |

Más hallazgos:

- `SendInput` con movimiento relativo aplica la aceleración del puntero de
  Windows: 120 px pedidos movieron 167. De ahí la posición absoluta de la ADR.
- No hay que fijarse en la sesión de consola (`WTSGetActiveConsoleSessionId`):
  con escritorio remoto, la sesión del usuario es otra.
- La sesión SSH de un administrador lleva integridad alta. La tarea con
  `LeastPrivilege` arranca sin elevar.
- Que un usuario estándar puede crear la tarea por SSH ya se vio el
  2026-09-23 ([[Experimento supervivencia de procesos en Win32-OpenSSH]],
  M3). Queda repetirlo con XML y con la cuenta estándar de pruebas durante la
  implementación.
- Limpieza: tarea `titan-mousepad-probe` borrada. La clave desechable sigue
  autorizada para las pruebas por SSH de esta tarea; se quita al terminarla.

## Por comprobar durante la implementación

- Qué pasa con la sesión bloqueada, con una sesión de escritorio remoto
  desconectada y sin nadie con la sesión iniciada (este último debe dar
  `E_NO_DESKTOP`).
- Que el front, el daemon, ConPTY y el desacople siguen funcionando con el
  binario en el subsistema gráfico (tests de `agent/` y prueba de punta a
  punta).

## Límites conocidos

- **UIPI**: el ayudante, sin elevar, no puede controlar ventanas elevadas
  (Administrador de tareas, instaladores con UAC).
- **Escritorio seguro**: no se controlan la pantalla de bloqueo, el diálogo de
  UAC ni Ctrl+Alt+Supr.

## Criterios de finalización

- ADR del mouse pad `Aceptada`.
- Se crea, edita, agrupa y lanza una sesión de tipo mouse pad, y la
  configuración antigua migra sin pérdidas.
- Contra un destino Windows: mover, clic izquierdo y derecho, arrastrar,
  scroll vertical y horizontal, texto con acentos y ñ, teclas especiales y
  combinaciones (Ctrl+C, Alt+Tab, Win).
- Sin escritorio iniciado, la pestaña muestra un error claro en lugar de
  fallar en silencio.
- La tarea programada se ve en el panel del agente y se puede quitar desde
  allí.
- Tests del protocolo en Go y Kotlin, y del inyector donde se pueda.
- Probado en el emulador contra el sshd de Windows de pruebas, con capturas, y
  confirmado por el usuario en el Pixel.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
