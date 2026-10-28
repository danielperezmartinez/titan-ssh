---
Nombre: 'Escritorio remoto en destinos Linux X11'
Estado: 'Hecha'
Estado desde: 2026-10-28
Resumen: 'Subtarea 1 del escritorio remoto: ver y controlar un escritorio Linux con X11, y construir la base común (tipo de sesión DESKTOP, tramas de pantalla del protocolo, modo titan-agent --screen, decodificación y vista en la app de escritorio y en Android). El agente habla con el servidor X del usuario con un cliente X11 propio y mínimo (GetImage, XFIXES para el cursor, XTEST para la entrada), sin ayudante. Se prueba con un contenedor desechable con Xvfb y sshd.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]]. En Android la pestaña reutiliza la vista del mouse pad con la pantalla detrás: el dedo usa los gestos del touchpad (no toque directo) y un zoom 1x-3x sigue al cursor; con ratón, posición absoluta. Implementado y verificado el 2026-10-21 (agente, app de escritorio por render, emulador). La prueba del usuario del 2026-10-28 falla en el scroll en kitty y al teclear ñ y vocales con tilde (ver Verificación); vuelve a En curso. Arreglado el 2026-10-28: el agente relee el mapa de teclado y el grupo XKB activo antes de cada tecla, hace scroll con los ejes de scroll de un dispositivo XI2 del destino (kitty no atiende los botones de rueda cuando hay touchpad), la app de escritorio toma el texto del evento tecleado y compone las teclas muertas, y la barra de teclas solo sale en Android.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-28T21:00:00+01:00
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
- **Prueba del usuario** (2026-10-28, canary 1.3.1-canary.42, destino
  `<destino-x11>`, desde el Pixel y desde la app de escritorio en Linux
  Wayland). Bien: imagen fluida, puntero, clic y clic derecho con todos los
  gestos, zoom, y la reconexión tras el modo avión. **Falla**:
  - **Scroll con dos dedos en kitty**: no hace nada, desde el móvil y desde el
    escritorio. En Firefox y en Alacritty sí funciona, así que es algo de cómo
    recibe kitty la rueda (o de lo que usa por debajo). Hay que investigarlo.
  - **Teclear `echo hola ñandú €`**: la `ñ` llega como `;` (la tecla que ocupa
    su sitio en la distribución US) y la `ú` como `´u` (la tilde muerta y la
    letra por separado). En un terminal sale `echo hola ;and´u €`. En Alacritty
    es peor: `;and´u` seguido de cuadros sin glifo alrededor del `€`
    (`□€□□`).
  - **Barra de teclas en la app de escritorio**: una sesión «escritorio» en
    la app de escritorio enseña la barra de teclas de Android (la de encima
    del teclado del móvil). `MousepadView` pinta `KeyBarRow` en todas las
    plataformas. El terminal solo la enseña en Android, y la misma vista ya
    oculta los botones del ratón en escritorio cuando hay imagen remota.
- **Arreglos tras la prueba del usuario** (2026-10-28, rama
  `canary-input-fixes`):
  - **ñ como `;`**: el agente elegía la tecla por las columnas del primer
    grupo del mapa *core* y lo leía una vez por sesión. Con dos
    distribuciones (`es,us`) y la segunda activa, o con un mapa cargado
    después (`setxkbmap`), pulsaba la tecla equivocada: reproducido en un
    Xvfb con `es,us` y el grupo 2 (`ñ` → `;`, `/` → `&`, `;` → `<`). Ahora
    lee el grupo activo (XKB `GetState`) y el mapa antes de cada tecla y
    solo usa las columnas de ese grupo. `TestLiveX11Typing` teclea además
    en el segundo grupo; en verde con `us`, `es`, `es,us` y `us,es`.
    `Restore` espera un momento antes de devolver las teclas prestadas: si
    no, la última tecla prestada podía no llegar a su cliente.
  - **Scroll en kitty**: kitty (glfw) ignora los botones 4-7 en cuanto el
    escritorio tiene un dispositivo con ejes de scroll XI 2.1 (un
    touchpad), y el puntero de XTEST no los tiene. Ahora el agente busca
    ese dispositivo y hace scroll moviendo sus ejes con XTEST, como el
    propio touchpad; el servidor sigue emulando los botones de rueda para
    los demás programas. Sin dispositivo así, botones como antes. Probado
    en Xwayland (sway sin pantalla), que tiene esos ejes: kitty no se movía
    con botones (380 → 380) y sí con los ejes (380 → 355); `xinput
    test-xi2` muestra el movimiento con el eje y los botones 4, 5 y 7
    emulados. Ojo: el servidor X lee los valores de un movimiento de XTEST
    como si empezaran en el valuador 0, así que se mandan desde ahí.
  - **`´u` y cuadros desde la app de escritorio**: el mouse pad tomaba el
    carácter de la pulsación. AWT da ahí una tecla muerta como su acento
    suelto (ˊ, U+02CA) y la letra sin componer, y la pulsación de Shift o
    AltGr como U+FFFF (los cuadros sin glifo). Ahora el texto sale del
    evento tecleado que sigue a la pulsación, las teclas muertas se
    componen en la app si no lo hizo un método de entrada, y las teclas sin
    carácter no escriben nada (`HardwareTypingTest`). Comprobado con AWT
    real en un Xvfb con distribución `es` y xdotool pulsando las teclas
    físicas: llega `echo hola ñandú € áéíóú ü @`.
  - **Barra de teclas en escritorio**: el mouse pad solo pinta la barra y
    el campo del teclado del móvil en Android, como el terminal.
  - Tests: `go test -race ./...`, `go vet` (Linux, macOS, FreeBSD; el
    aviso de Windows es anterior), `:shared:desktopTest` (534 en verde).
    Emulador: la build canary del worktree conecta a un destino X11 de
    pruebas, enseña la barra de teclas y el teclado del móvil escribe en el
    xterm remoto.

- **Prueba del usuario** (2026-10-28, canary 1.4.1-canary.45): todo correcto, tal como dice «Cómo probarlo».

## Cómo probarlo

**Dónde**: la canary desde el commit `b977b65` (o posterior en `main`), en el
móvil y en el PC. Destino: un Linux con la sesión gráfica iniciada en X11
(`<destino-x11>`; en la pantalla de inicio de sesión, la variante «X11» o
«Xorg»), con kitty instalado.

**Preparación**: en la canary, una sesión de tipo «escritorio» para ese
destino. La canary actualiza sola el agente del destino al conectar.

**Pasos**:

1. Abrir la sesión desde el móvil: se ve la pantalla del destino y su cursor.
2. Mover el cursor con un dedo, tocar para hacer clic y hacer zoom (de 1x a
   3x): el zoom sigue al cursor.
3. Abrir un terminal en el destino y escribir con el teclado del móvil
   `echo hola ñandú € /;:` y Enter. Repetirlo en Alacritty.
4. Abrir kitty con texto de sobra (p. ej. `seq 1 500`) y hacer scroll con dos
   dedos arriba y abajo; repetir en Firefox.
5. Poner el móvil en modo avión unos segundos y quitarlo.
6. Repetir 3 y 4 desde la app de escritorio, con el teclado físico (también
   `áéíóú ü @` con las teclas muertas y AltGr) y la rueda o el touchpad. En
   la app de escritorio la sesión no enseña la barra de teclas de Android.

**Qué debe pasar**: imagen en vivo, cursor donde toca, el texto llega exacto
(sin `;` en lugar de `ñ`, sin `´` suelto ni cuadros), kitty hace scroll como
Firefox, y tras el corte reconecta sola en pocos segundos con la imagen al
día.

**Si falla**: qué paso, captura de la app, versión de la canary, el
escritorio y la distribución del destino, y la salida de `setxkbmap -query`
y de `xinput list` en el destino.

## Resultado

Escritorio remoto en X11 con un cliente X11 propio (GetImage, XFIXES y
XTEST), con la base común del tipo de sesión «escritorio». Tras la prueba del
2026-10-28 se arreglaron el grupo XKB de cada tecla (la ñ), el scroll de kitty
con los ejes del touchpad XI2 y la barra de teclas en la app de escritorio. El
usuario lo confirma en la canary 1.4.1-canary.45.
