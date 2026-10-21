---
Nombre: 'Escritorio remoto en destinos Linux X11'
Estado: 'En curso'
Resumen: 'Subtarea 1 del escritorio remoto: ver y controlar un escritorio Linux con X11, y construir la base común (tipo de sesión DESKTOP, tramas de pantalla del protocolo, modo titan-agent --screen, decodificación y vista en la app de escritorio y en Android). El agente habla con el servidor X del usuario con un cliente X11 propio y mínimo (GetImage, XFIXES para el cursor, XTEST para la entrada), sin ayudante. Se prueba con un contenedor desechable con Xvfb y sshd.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]]. En Android la pestaña reutiliza la vista del mouse pad con la pantalla detrás: el dedo usa los gestos del touchpad (no toque directo) y un zoom 1x-3x sigue al cursor; con ratón, posición absoluta. Implementado y verificado el 2026-10-21 (agente, app de escritorio por render, emulador); falta la confirmación del usuario en un escritorio X11 real.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-21T14:10:00+02:00
---

# Escritorio remoto en destinos Linux X11

Parte de [[Escritorio remoto a través del agente]].

## Objetivo

Que una sesión de escritorio remoto enseñe y controle un escritorio Linux que
corre sobre X11, y dejar hecha la base que reutilizan Windows y Wayland.

## Plan

1. **Agente**: cliente X11 mínimo (conexión por socket Unix, cookie
   `MIT-MAGIC-COOKIE-1` de `XAUTHORITY` o `~/.Xauthority`, `GetImage`,
   XFIXES `GetCursorImage`, XTEST `FakeInput`, mapa de teclado); búsqueda del
   `DISPLAY` del usuario en `/proc`; codificador de baldosas
   (`FILL`/`ZLIB`/`JPEG`) con control de flujo por acuses; modo `--screen`.
2. **Protocolo**: tramas nuevas en `protocol.go` y `AgentProtocol.kt`, con
   tests a los dos lados.
3. **App**: `SessionType.DESKTOP`, `ScreenTransport` (como `InputTransport`),
   decodificador de baldosas y búfer de imagen, vista con el cursor remoto,
   entrada por ratón y teclado en escritorio y por gestos en Android.
4. **Pruebas**: contenedor con Xvfb y sshd (`tools/test-x11/`), tests de Go
   contra Xvfb y un test de integración opcional de Kotlin que recibe y
   decodifica fotogramas reales.

## Criterios de finalización

- Contra un escritorio X11: se ve la pantalla, se actualiza al cambiar, el
  cursor se ve y sigue al ratón, y funcionan mover, clic izquierdo y derecho,
  arrastrar, scroll, texto con acentos y ñ y combinaciones de teclas.
- Un microcorte congela la imagen y la reconexión trae un fotograma entero sin
  intervención del usuario.
- Un cambio de resolución del destino se refleja en la app.
- Sin sesión X11, error claro (`E_NO_DESKTOP`).
- Tests de Go del cliente X11 y del codificador; test de integración de
  Kotlin contra el contenedor.
- Probado en el emulador de Android y en la app de escritorio, y confirmado
  por el usuario en un escritorio X11 real.

## Verificación

Hecho el 2026-10-21 contra el contenedor de `tools/test-x11` (Xvfb 1280×800
con cookie, twm, reloj que cambia cada segundo, xterm). Pendiente solo la
confirmación del usuario en un escritorio X11 real.

- **Agente** (`go test -race ./...` en verde, también `go vet` para Windows,
  macOS y FreeBSD):
  - `internal/x11`: tests unitarios con un servidor X falso y en vivo dentro del
    contenedor (`TITAN_X11_LIVE=1`): sesión encontrada por `/proc`, cookie,
    `GetImage`, XFIXES, XTEST y mapa de teclado.
  - `internal/inject`: en un xterm se escribe exacto `Hola ñandú € ✓ A&B` y
    Ctrl+D (los caracteres fuera de la distribución, con keycodes prestados).
  - `internal/screen`: ida y vuelta sin pérdida exacta, `FILL` en lo liso,
    `JPEG` solo en lo fotográfico y refinado posterior sin pérdida, unión de
    baldosas vecinas, control de flujo (sin acuses deja de capturar),
    redimensionado con fotograma entero, cursor y entrada.
  - `internal/protocol`: bytes de cada trama fijados en hex, los mismos que
    comprueba `ScreenFramesTest.kt`.
- **Medidas** (sondeo por `docker exec`, sin SSH): primer fotograma entero de
  1280×800 en unos 94 ms y 11 KB (fondo, reloj y xterm); cada tic del reloj,
  2-4 KB (2-3 baldosas `ZLIB`).
- **App** (`:shared:desktopTest`, 467 tests en verde):
  - `ScreenFramesTest`: bytes de las tramas, `RemoteScreen` (FILL, ZLIB, JPEG,
    cursor, publicación al final del fotograma, rechazo de baldosas fuera de la
    pantalla) y `ScreenViewport`.
  - `ScreenTransportIntegrationTest` por SSH real contra el contenedor
    (`-PtitanSshTestHost=127.0.0.1 -PtitanSshTestPort=2224 -PtitanSshTestUser=demo
    -PtitanSshTestPassword=demo -PtitanSshTestX11=1 -PtitanAgentBin=<binario>`, con un
    agente compilado con `-tags canary`):
    instala el agente, primer fotograma en ~40 ms, `POINTER_TO` vuelve como
    posición del cursor, siguen llegando fotogramas y tras cortar la conexión
    la siguiente empieza con un fotograma entero.
  - Render fuera de pantalla (test desechable, no versionado) de la pestaña
    real conectada al contenedor: imagen, cursor remoto, y texto tecleado por
    la pestaña ejecutado en el xterm remoto.
- **Emulador** (`Pixel_9_Pro_XL`, build de debug del worktree, permiso de red
  local añadido solo para la prueba): host `10.0.2.2:2224` con contraseña y
  sesión de tipo escritorio creados desde la interfaz; conecta, enseña la
  pantalla y el cursor remotos, un dedo mueve el cursor, tocar hace clic, el
  zoom 2x sigue al cursor, el teclado del móvil escribe en el xterm remoto
  (`echo ok` → `ok`), y al matar el `sshd` de la sesión reconecta solo en
  menos de 2 s y sigue la imagen en vivo.
- **Teclado del móvil en ráfaga** (2026-10-21): escribir rápido duplicaba
  letras (`echo desde el movil` llegaba como `eececho ddee eel mmovvivil`).
  El campo oculto que levanta el teclado (`SoftKeyboardCapture`, el mismo del
  terminal) se vaciaba en cada tecla, y las teclas que llegaban antes de
  recomponer se construían sobre el texto anterior, así que se reenviaban.
  Ahora cada cambio se compara con lo que tenía el campo (`SoftKeyboardEdits`,
  con `SoftKeyboardEditsTest`). Comprobado en el emulador: una ráfaga de
  `adb shell input text`, una línea de 80 caracteres con un borrado en medio y
  teclas pulsadas en Gboard llegan exactas al xterm remoto. El fallo venía del
  terminal y del mouse pad, así que el arreglo también les llega.

## Resultado

<Se rellena al completar.>
