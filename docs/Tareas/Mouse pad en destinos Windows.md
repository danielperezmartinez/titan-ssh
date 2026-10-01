---
Nombre: 'Mouse pad en destinos Windows'
Estado: 'Planificando'
Resumen: 'Subtarea 1 del mouse pad: controlar ratón y teclado de un destino Windows desde el móvil, y construir la base común (ADR, tipo de sesión, protocolo de entrada y pestaña de gestos). El agente que lanza sshd corre en la sesión 0, sin escritorio, así que SendInput desde ahí no llega a la pantalla del usuario. Para entrar en su escritorio sin ser administrador, el agente registra una tarea programada del usuario, "solo cuando haya iniciado sesión", y la lanza; arranca titan-agent en modo ayudante dentro de la sesión interactiva, que recibe los eventos del agente por loopback con token y los aplica con SendInput. La tarea se ve y se quita desde el panel del agente.'
Decisiones: 'Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. El usuario aceptó el 2026-10-01 la tarea programada visible y gestionable desde el panel del agente, frente a una entrada de arranque automático (siempre activa) o un servicio (exige administrador).'
Bloqueada: []
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-01T18:25:00+02:00
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
   acepta antes de tocar código.
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

## Por comprobar al empezar

- Que un usuario estándar puede registrar y lanzar la tarea desde una sesión
  SSH: `schtasks` o la API COM del Programador de tareas, y con qué tipo de
  inicio de sesión. Conviene un experimento corto, como
  [[Experimento supervivencia de procesos en Win32-OpenSSH]], con la cuenta
  estándar de pruebas.
- Qué pasa sin nadie con la sesión iniciada, con la sesión bloqueada, con una
  sesión de escritorio remoto desconectada y con varios usuarios conectados.

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
