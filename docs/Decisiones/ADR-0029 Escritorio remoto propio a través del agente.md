---
Nombre: 'Escritorio remoto propio a través del agente'
Número: 29
Estado: 'Propuesta'
Resumen: 'Nuevo tipo de sesión, escritorio remoto, que enseña la pantalla del destino y la controla sin RDP ni VNC de terceros, sobre la conexión SSH y el agente. La app hace exec de titan-agent --screen: el agente captura la pantalla, la parte en baldosas de 64×64, manda solo las que cambian (color liso, píxeles sin pérdida comprimidos con deflate, o JPEG para lo fotográfico) y recibe por el mismo canal las tramas de entrada del mouse pad, más una nueva de posición absoluta. El ritmo lo marca la app con acuses (como mucho dos fotogramas en vuelo), así que nunca se acumulan fotogramas viejos y un microcorte solo congela la imagen hasta que la reconexión trae un fotograma entero. Todo con la biblioteca estándar de Go y el JDK/Android, sin cgo ni dependencias nuevas. Captura por sistema: X11 con un cliente X11 propio mínimo (GetImage, XFIXES para el cursor, XTEST para la entrada), sin ayudante; Windows desde el ayudante de escritorio de ADR-0016 con Desktop Duplication y GDI de respaldo; Wayland con el portal ScreenCast + RemoteDesktop y PipeWire, en su propia tarea. Se descarta el vídeo (H.264, VP8/9, AV1) por cgo, tamaño del agente, decodificación en el escritorio JVM y patentes.'
Decisión: 'Añadir el tipo de sesión DESKTOP y el modo titan-agent --screen, que manda baldosas cambiadas (FILL, ZLIB o JPEG) con control de flujo por acuses y recibe la entrada del mouse pad en el mismo canal; capturar con un cliente X11 propio en X11, con el ayudante de escritorio (Desktop Duplication, GDI de respaldo) en Windows y con el portal ScreenCast y PipeWire en Wayland, sin cgo ni dependencias nuevas.'
Consecuencias: 'Ver y controlar el escritorio del destino desde Android y escritorio sin instalar ni abrir nada más en el destino, con los mismos límites de seguridad que una shell del usuario. Ancho de banda mayor que el de un códec de vídeo en contenido en movimiento (vídeos, juegos), a cambio de texto nítido, latencia baja y un agente portable. Más código propio que mantener: un cliente X11 mínimo, la captura de Windows por COM y, más adelante, un cliente PipeWire. El protocolo del agente crece con tramas de pantalla que AgentProtocol.kt y protocol.go deben mantener en sincronía.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-21T10:45:00+02:00
Última modificación: 2026-10-21T14:30:00+02:00
---

# ADR-0029 · Escritorio remoto propio a través del agente

## Contexto

Con el mouse pad ([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]])
el móvil ya mueve el ratón y teclea en el destino, pero el usuario tiene que
mirar la pantalla del PC. Falta la otra mitad: **ver** el escritorio del
destino desde titan-ssh, en Android y en escritorio, sin un cliente RDP o VNC
de terceros y sin abrir puertos en el destino. Otra línea de trabajo explora
un cliente RDP embebido (opción C); esta ADR decide la opción D, un escritorio
remoto propio sobre lo que ya existe.

Lo que ya hay y se reutiliza:

- La conexión SSH autenticada, su reconexión y los microcortes como pilar del
  producto ([[ADR-0003 Modelo de resiliencia por niveles]]).
- `titan-agent`, un binario Go estático por SO y arquitectura, sin cgo y con
  `golang.org/x/sys` como única dependencia
  ([[ADR-0008 Diseño del agente de resiliencia nivel 3]],
  [[ADR-0009 Agente de nivel 3 portable a todos los destinos]],
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]).
- El encuadre de tramas del protocolo y las tramas de entrada del mouse pad,
  con su liberación de botones y teclas al cortarse (`--input`).
- El ayudante de escritorio de Windows, que corre en la sesión interactiva del
  usuario y se alcanza por loopback con autenticación mutua
  ([[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]],
  [[ADR-0018 Autenticación mutua en el punto de encuentro del agente]]).
- `SessionType` en la configuración: un campo nuevo con valor por defecto no
  cambia la versión de la configuración
  ([[ADR-0007 Modelo y persistencia de configuración]]).

### Investigación: captura

| Sistema | Vía | Valoración |
|---|---|---|
| Windows | **DXGI Desktop Duplication** (`IDXGIOutputDuplication`) | La vía eficiente: copia en GPU, entrega rectángulos sucios y movidos y la forma y posición del cursor. Funciona en la sesión interactiva del usuario, también si es de escritorio remoto. Se usa desde Go sin cgo llamando a las vtables COM con `syscall.SyscallN` (no hay una librería Go madura para la duplicación; WebRTC es la referencia del flujo). Se pierde (`DXGI_ERROR_ACCESS_LOST`) al cambiar de modo o de escritorio y hay que recrearla. No ve el escritorio seguro (UAC) ni la pantalla de bloqueo, igual que la entrada. |
| Windows | Windows.Graphics.Capture | Moderna y por ventana, pero WinRT desde Go sin cgo es mucho más trabajo (activación, `IInspectable`, interop D3D11, callbacks del frame pool) y en Windows 10 dibuja un borde amarillo. Descartada para la v1. |
| Windows | GDI (`BitBlt` desde el DC de la pantalla) | La más simple y la que funciona en todas partes (también en máquinas virtuales sin adaptador 3D), pero lenta (decenas de ms por fotograma 1080p) y sin rectángulos sucios. **Respaldo** cuando Desktop Duplication no esté disponible. |
| Linux X11 | `GetImage` del protocolo X11 | Lee la ventana raíz entera (todos los monitores) por el socket de X. Sin dependencias. Unos 8 MB por fotograma 1080p por un socket local: unos pocos ms. |
| Linux X11 | MIT-SHM (`ShmGetImage`) | Evita copiar por el socket con memoria compartida SysV (`golang.org/x/sys/unix` ya tiene `SysvShmGet`/`SysvShmAttach`). Solo si el servidor X está en la misma máquina y en el mismo espacio IPC; optimización con `GetImage` de respaldo. |
| Linux X11 | DAMAGE | Dice qué regiones cambiaron; ahorra comparar baldosas. Optimización posterior. |
| Linux X11 | XFIXES `GetCursorImage` | La forma y posición del cursor, que `GetImage` no incluye. |
| Linux Wayland | Portal **ScreenCast** (+ **RemoteDesktop** en la misma sesión) de xdg-desktop-portal | La única vía común a GNOME y KDE. Pide consentimiento en la pantalla del destino; con `persist_mode` = 2 guarda un token para no volver a preguntar. Combinado con RemoteDesktop, un solo diálogo da imagen y entrada. Entrega los fotogramas por **PipeWire**: no hay un cliente PipeWire en Go puro, así que hay que escribir uno mínimo (protocolo nativo, PODs de SPA y búferes memfd) o usar cgo. Es el caso más caro. |
| Linux Wayland | `ext-image-copy-capture-v1` / `wlr-screencopy` | Protocolo Wayland simple con búferes shm, fácil en Go puro, pero solo en compositores wlroots y afines (sway, Hyprland, labwc…); GNOME y KDE no lo ofrecen a clientes normales. Posible respaldo. |

### Investigación: codificación

| Opción | Ancho de banda | Latencia y CPU | Agente y cliente | Licencias |
|---|---|---|---|---|
| **Baldosas cambiadas** con color liso, deflate sin pérdida o JPEG (como Tight de VNC) | Muy bajo en uso de escritorio (escribir, ventanas, texto: pocas baldosas por fotograma); alto en vídeo o juegos (cada fotograma, JPEG entero) | Latencia mínima (sin GOP ni búfer de reordenación); CPU proporcional a lo que cambia | `compress/zlib` e `image/jpeg` de la biblioteca estándar de Go; `java.util.zip.Inflater` (común a JVM y Android), `BitmapFactory` en Android e `ImageIO` en el escritorio | Sin patentes ni dependencias |
| zstd o lz4 en lugar de deflate | 10-30 % menos que deflate, más rápido | Mejor | Dependencia nueva en Go (`klauspost/compress`, BSD) y en Kotlin (zstd-jni es nativo; aircompressor es Java puro) | Sin problemas, pero dos dependencias con su ADR |
| PNG / WebP sin pérdida | Algo menor que deflate en bruto | `image/png` de Go es lento; WebP no tiene codificador en la biblioteca estándar | WebP: dependencia nueva | — |
| **H.264 por hardware** (Media Foundation en Windows, VAAPI en Linux) | El menor en movimiento | Baja con hardware; el texto queda borroso a bitrates bajos | MF por COM sin cgo es viable pero complejo; VAAPI exige cgo o hablar con DRM directamente. Android lo decodifica con MediaCodec, pero **el escritorio JVM no trae decodificador**: haría falta FFmpeg nativo (JavaCV, decenas de MB por plataforma) | Patentes de H.264 (pools de licencias); repartir un codificador o decodificador en un proyecto GPL gratuito es un riesgo que el proyecto no quiere asumir |
| H.264 por software (x264, OpenH264) | Igual | CPU alta en el destino | cgo y una librería C por cada SO y arquitectura, contra la portabilidad de ADR-0009 | x264 GPL (compatible), OpenH264 solo cubre patentes con el binario de Cisco |
| VP8/VP9/AV1 | Bajo | CPU alta por software | libvpx, libaom o SVT-AV1 en C: cgo; sin codificadores Go maduros; mismo problema de decodificación en JVM | Libres de royalties |

### Investigación: transporte

- **Canal `exec`** como `--input`: el agente escribe tramas en su stdout y lee
  la entrada de su stdin. Va dentro de la conexión SSH, sin puertos, y la
  ventana del canal SSH ya da contrapresión: si la app no lee, el agente se
  bloquea al escribir.
- **Reenvío a un puerto loopback** (`direct-tcpip`): obligaría a un servidor
  que escuche en el destino, con su autenticación. No aporta nada sobre `exec`.
- **Control de flujo**: la contrapresión de SSH sola deja que se acumulen
  fotogramas en los búferes (el usuario vería el pasado). Hace falta que la app
  pida o reconozca los fotogramas, como `FramebufferUpdateRequest` de VNC.
- **Microcortes**: la pantalla no necesita historial. Al volver basta un
  fotograma entero; el escritorio del destino nunca se entera del corte.

## Decisión

### 1. Tipo de sesión

- `SessionType` gana `DESKTOP` ("Escritorio remoto"). Es un valor nuevo de un
  campo que ya tiene valor por defecto: **la versión de la configuración no
  cambia**. Como el mouse pad, usa su host y los campos de organización; los de
  terminal se ocultan sin borrarse. Igual que pasó con `SFTP`, una app anterior
  no sabe leer una configuración con una sesión `DESKTOP` (importarla en ella
  falla); la app nueva lee todas las anteriores.
- Guarda sus ajustes en `desktop: DesktopSettings`, con valor por defecto: la
  calidad (`AUTO`, `HIGH` = sin pérdida, `LOW` = JPEG más comprimido). Usa
  también los ajustes del puntero del mouse pad.
- Se ofrece en todas las plataformas, con la misma vista que el mouse pad y la
  pantalla detrás de su superficie. Con un ratón (la app de escritorio, o un
  ratón en el móvil) se apunta directamente a la imagen: posición absoluta,
  botones y rueda. Con el dedo se usan los gestos del touchpad del mouse pad,
  que el usuario ya conoce (un dedo mueve el cursor, tocar es clic, dos dedos
  hacen scroll, mantener pulsado arrastra), con el cursor remoto dibujado
  sobre la imagen y un zoom de 1x a 3x que sigue al cursor. Teclado y barra de
  teclas, los del mouse pad.
- **Solo en la canary mientras se compara** con el cliente RDP embebido
  ([[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]]): `DESKTOP` es un tipo
  `experimental` (`SessionType.experimental`, mecanismo común a las dos
  opciones), y `SessionType.offered` solo lo ofrece si
  `AppChannel.isCanary`. La app estable no lo lista en el editor y, si recibe
  una sesión de ese tipo (una configuración importada de la canary), la
  enseña pero no la abre. Cuando el usuario elija, la opción ganadora deja de
  ser experimental y la otra se retira entera: tipo, código, agente, CI y
  documentación, con su ADR en `Rechazada`.

### 2. Modo `--screen` y protocolo

La app hace `exec` de `titan-agent --screen`. **Un solo canal** lleva la
pantalla (agente → app) y la entrada (app → agente): las tramas de entrada de
ADR-0016 sin cambios, más las nuevas. Mismo encuadre (tipo de 1 byte, longitud
u32 y carga), big-endian; la disposición exacta vive en `screen.go` (Go) y en
`AgentProtocol.kt`, en sincronía.

| Tipo | Sentido | Carga |
|---|---|---|
| `SCREEN_HELLO` | app → agente | versión u8, calidad u8 (0 = sin pérdida, 1-100 calidad JPEG máxima) |
| `SCREEN_SIZE` | agente → app | ancho u16, alto u16. Antes del primer fotograma y cada vez que cambia la resolución; lo sigue un fotograma entero |
| `SCREEN_TILE` | agente → app | x u16, y u16, ancho u16, alto u16, codificación u8, datos |
| `SCREEN_FRAME_END` | agente → app | secuencia u32: el fotograma está completo y se puede enseñar |
| `SCREEN_ACK` | app → agente | secuencia u32: la app ya pintó ese fotograma |
| `CURSOR_SHAPE` | agente → app | ancho u16, alto u16, punto activo x u16 e y u16, ARGB comprimido con deflate |
| `CURSOR_POS` | agente → app | x i16, y i16 (el punto activo puede salirse por arriba o por la izquierda), visible u8 |
| `POINTER_TO` | app → agente | x u16, y u16: el puntero a esa posición de la pantalla capturada |

Codificaciones de una baldosa (de 64×64 píxeles, menos en los bordes):

- `FILL` (0): un color RGB. Fondos y zonas lisas.
- `ZLIB` (1): los píxeles en RGB de 24 bits, fila a fila, comprimidos con
  deflate (zlib). Sin pérdida: texto e interfaz nítidos.
- `JPEG` (2): la baldosa en JPEG. Para lo fotográfico (muchos colores).
- Los números siguientes quedan libres para más adelante (por ejemplo, zstd o
  una copia de otra zona para el scroll); una app que no conozca una
  codificación cierra la conexión con un error, y por eso `SCREEN_HELLO` lleva
  versión.

El agente elige por baldosa: lisa → `FILL`; pocos colores (interfaz y texto) o
calidad sin pérdida → `ZLIB`; muchos colores → `JPEG`. Cuando la pantalla se
queda quieta, vuelve a mandar sin pérdida las baldosas que mandó en JPEG, para
que lo que se queda en pantalla se vea nítido.

### 3. Control de flujo y ritmo

- El agente solo captura cuando tiene crédito: como mucho **dos fotogramas sin
  acuse** (`SCREEN_ACK`). Así nunca hay fotogramas viejos en cola y el ritmo se
  adapta solo al enlace y a lo que tarda la app en pintar: en un enlace lento
  se ven menos fotogramas, pero siempre recientes.
- Un fotograma solo lleva las baldosas que cambiaron respecto al anterior
  enviado; uno sin cambios no se manda, y el agente vuelve a mirar a un ritmo
  máximo de unos 30 fotogramas por segundo (menos si no cambia nada).
- La app aplica las baldosas a un búfer y lo enseña al llegar
  `SCREEN_FRAME_END`, sin fotogramas a medio pintar.
- La calidad `AUTO` baja la calidad JPEG cuando los acuses tardan (enlace
  lento) y la sube cuando vuelven a llegar rápido.

### 4. Microcortes, reconexión y cambios de pantalla

- No hay replay. Durante un corte la app conserva la última imagen, atenuada
  y con el aviso de reconexión, y no envía entrada (como el mouse pad). Al
  reconectar, un `--screen` nuevo empieza con `SCREEN_SIZE` y un fotograma
  entero. El escritorio del destino no se entera del corte.
- Al cortarse, el agente suelta los botones y teclas pulsados (como
  `--input`).
- Cambio de resolución: `SCREEN_SIZE` y fotograma entero.
- Cursor: el agente manda su forma cuando cambia y su posición cuando se mueve;
  la app lo dibuja encima de la imagen, así que moverlo no cuesta baldosas.
- Varios monitores: la v1 manda el escritorio entero en X11 (la ventana raíz
  abarca todos) y el monitor principal en Windows (Desktop Duplication va por
  salida). Elegir monitor queda para una trama posterior.

### 5. Captura por sistema

- **Linux X11**: el propio proceso `--screen` habla con el servidor X del
  usuario; no hace falta ayudante. Busca su `DISPLAY` y su `XAUTHORITY` en el
  entorno de los procesos del mismo usuario (`/proc/<pid>/environ`), y si no,
  `~/.Xauthority`. Un **cliente X11 propio y mínimo** en Go (conexión por el
  socket Unix, autenticación `MIT-MAGIC-COOKIE-1`, `QueryExtension`,
  `GetImage`, XFIXES, XTEST y el mapa de teclado), sin dependencias nuevas.
  Con XTEST, el mismo cliente sirve de inyector, y desbloquea
  [[Mouse pad en destinos Linux X11]].
- **Windows**: el front `--screen` (en la sesión 0) llega al ayudante de
  escritorio con una marca nueva del apretón de manos de ADR-0018; el
  ayudante captura con **Desktop Duplication** por COM desde Go, sin cgo, y
  con **GDI** si no está disponible, e inyecta con el `SendInput` que ya tiene
  (añadiendo la posición absoluta). Con el escritorio bloqueado o el UAC
  delante no hay imagen: la app lo dice con el mismo estado `blocked` del
  mouse pad.
- **Linux Wayland**: portal ScreenCast y RemoteDesktop en una sola sesión del
  portal (un consentimiento en el destino, recordado con su token), y un
  cliente PipeWire mínimo propio. Su diseño fino (y si se admite cgo solo
  para Wayland) se cierra en su tarea; hasta entonces `--screen` termina con
  `E_SCREEN_UNSUPPORTED`, igual que en macOS y BSD.

### 6. Seguridad y transparencia

- Sin puertos nuevos ni administrador. En X11 el agente usa la cookie del
  propio usuario; en Windows, el ayudante ya existente; en Wayland, el
  consentimiento del portal.
- No da nada que una shell del mismo usuario no pudiera conseguir (podría
  lanzar `xwd` o un programa de capturas), pero sí lo hace cómodo, y una
  pantalla puede enseñar datos de otras personas. Por eso: cada sesión de
  pantalla abierta tiene un candado en el directorio de estado del usuario
  (`screen-<pid>.lock`, con su hora de inicio), y `--status` las lista para
  que el panel del agente las enseñe.
- Un aviso visible en la pantalla del destino mientras alguien la mira
  (en X11 y Windows; Wayland ya lo pone el portal) queda como decisión abierta
  con el usuario.

## Alternativas consideradas

- **Códec de vídeo (H.264, VP8/VP9, AV1)**: mejor ancho de banda en movimiento,
  pero exige cgo o mucho código COM/VAAPI en el agente, librerías nativas de
  decenas de MB en el escritorio JVM para decodificar, y en H.264 un riesgo de
  patentes. El caso de uso principal (controlar el PC, leer texto) se sirve
  mejor con baldosas sin pérdida. Descartado para la v1; si hiciera falta, se
  podría añadir más adelante como codificación extra solo donde el hardware
  lo dé (Media Foundation en Windows y MediaCodec en Android).
- **zstd/lz4 o WebP**: ganan poco frente a deflate y JPEG a cambio de
  dependencias nuevas en el agente y en la app. El número de codificación
  queda libre para añadirlas.
- **Un servidor VNC o RDP en el destino** (x11vnc, TigerVNC, el RDP de
  Windows) con un cliente embebido: obliga a instalar o activar algo en el
  destino y a abrir un puerto, y el RDP de Windows no está en las ediciones
  Home. Es la línea de la opción C, independiente de esta.
- **Puerto loopback reenviado** en lugar de `exec`: un servidor más en el
  destino sin ganar nada.
- **Sin control de flujo** (solo la ventana del canal SSH): los fotogramas se
  acumulan y la imagen va con retraso tras un enlace lento.
- **Librería X11 en Go** (`jezek/xgb`, BSD): cubre todo el protocolo, pero es
  grande y añade una dependencia al agente con su ADR; las peticiones que
  hacen falta son pocas.
- **Windows.Graphics.Capture**: más trabajo desde Go sin cgo y con el borde
  amarillo en Windows 10; Desktop Duplication con GDI de respaldo cubre lo
  mismo.
- **Un ayudante también en X11**: no hace falta, el proceso de la sesión SSH
  ya puede hablar con el servidor X del usuario.

## Consecuencias

- Positivas: ver y controlar el escritorio del destino desde el móvil y el
  escritorio sin instalar ni abrir nada más; reutiliza conexión, agente,
  ayudante, protocolo y reconexión; texto nítido y latencia baja; un microcorte
  no pierde nada; agente sin cgo ni dependencias nuevas y app sin librerías
  nativas.
- Negativas / compromisos: en contenido en movimiento (vídeo, juegos) gasta
  más ancho de banda que un códec de vídeo y baja de fotogramas en enlaces
  lentos; más código propio (cliente X11, captura COM en Windows, cliente
  PipeWire en Wayland); la captura de Windows y de Wayland no se puede probar
  en el PC de desarrollo y necesita destinos reales; el protocolo del agente
  crece con tramas nuevas.
