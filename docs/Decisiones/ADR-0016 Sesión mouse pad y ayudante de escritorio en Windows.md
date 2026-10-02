---
Nombre: 'Sesión mouse pad y ayudante de escritorio en Windows'
Número: 16
Estado: 'Aceptada'
Resumen: 'Nuevo tipo de sesión, mouse pad, que controla el ratón y el teclado del destino. Session gana el campo type (TERMINAL o MOUSEPAD, por defecto TERMINAL, sin cambiar la versión de la configuración). La app lanza titan-agent --input por exec sobre la conexión SSH y le manda tramas de entrada con el mismo encuadre del protocolo del nivel 3, sin replay; al cortarse la entrada, el agente suelta los botones y teclas pulsados. En Windows el agente corre en la sesión 0, sin escritorio, así que inyecta un ayudante, titan-agent --desktop, que arranca en la sesión interactiva del usuario mediante una tarea programada del propio usuario (solo interactiva, sin administrador ni contraseña). El front de entrada se encuentra con él por TCP loopback con token, y el ayudante aplica los eventos con SendInput: posición absoluta, para que solo cuente la curva de la app, y texto Unicode. El binario de Windows pasa al subsistema gráfico para que la tarea no abra una consola. La tarea y el ayudante se ven y se quitan desde el panel del agente. Linux X11 y Wayland reutilizan el protocolo y deciden su inyector en sus tareas.'
Decisión: 'Añadir el tipo de sesión MOUSEPAD con un modo --input del agente y tramas de entrada neutras respecto al SO; en Windows, inyectar con un ayudante en la sesión interactiva lanzado por una tarea programada del usuario y alcanzado por loopback con token, compilando el binario de Windows con el subsistema gráfico.'
Consecuencias: 'Reutiliza la conexión SSH, la instalación del agente y el encuentro loopback; no abre puertos ni pide administrador. Deja una huella visible en el destino Windows (la tarea programada), que el panel enseña y quita. No se controlan la pantalla de bloqueo, el escritorio seguro (UAC) ni las ventanas elevadas, y hace falta que el usuario tenga su sesión de escritorio iniciada. Cambiar el subsistema del binario de Windows obliga a volver a verificar el front, el daemon y ConPTY, y a que la CLI se enganche a la consola que la lanza para seguir imprimiendo.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-01T19:20:00+02:00
Última modificación: 2026-10-02T19:32:17+02:00
---

# ADR-0016 · Sesión mouse pad y ayudante de escritorio en Windows

> **Nota (2026-10-01)**: el punto **Sin consola** lo sustituye
> [[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]. Con
> el subsistema gráfico, PowerShell no recoge el código de salida del agente.
> En la implementación, la tarea se llama
> `titan-ssh-desktop-<usuario>-<parte aleatoria>`: los nombres de las tareas
> son comunes a todos los usuarios del PC. El nombre se elige al primer uso y
> se guarda en `desktop-task.txt`, en el directorio de estado; `--remove-desktop`
> lo olvida. Tras registrarla, el front comprueba que la tarea se ejecuta con
> la cuenta del usuario antes de lanzarla (nota del 2026-10-02).

## Contexto

El usuario quiere usar el móvil como touchpad y teclado del PC al que se
conecta ([[Sesión mouse pad para controlar el escritorio del destino]]). Sus
destinos son sobre todo Windows, y quiere también Linux X11 y Wayland, que van
en sus propias tareas.

titan-ssh ya tiene casi todo el camino: la conexión SSH autenticada, el agente
`titan-agent` instalado por SO y arquitectura
([[ADR-0009 Agente de nivel 3 portable a todos los destinos]]) y el encuentro
por TCP en `127.0.0.1` con token entre procesos del agente. Falta inyectar la
entrada en el escritorio del destino, y en Windows eso choca con dónde corre el
agente: Win32-OpenSSH lanza los procesos de la sesión SSH en la **sesión 0**,
sin escritorio.

Experimento del 2026-10-01 (PC de pruebas con Windows 10 22H2 y
Win32-OpenSSH 10.0p2, usuario conectado por escritorio remoto; detalle en
[[Mouse pad en destinos Windows]]):

- Lanzada directamente por SSH, una sonda queda en la sesión 0: `GetCursorPos`
  falla ("requiere una estación de ventana interactiva") y `SendInput` no hace
  nada.
- Una tarea programada creada por SSH con `schtasks`, en modo **solo
  interactivo**, y lanzada con `schtasks /run` arranca en la sesión
  interactiva del usuario, también si es de escritorio remoto, y mueve el
  cursor. Sin sesión de escritorio iniciada no se ejecuta
  ([[Experimento supervivencia de procesos en Win32-OpenSSH]], prueba M3).
- Creada desde XML (`schtasks /xml`, que exige UTF-16) se pueden quitar los
  límites por defecto: no arrancar con batería, parar al pasar a batería y
  parar a las 72 horas.
- `SendInput` con movimiento relativo pasa por la aceleración del puntero de
  Windows: 120 px pedidos movieron 167.
- Un binario Go con el subsistema gráfico (`-H windowsgui`) sigue leyendo y
  escribiendo su stdio cuando se lo dan redirigido dentro de una sesión SSH.

## Decisión

### 1. Tipo de sesión

- `Session` gana `type: SessionType`, con `TERMINAL` (por defecto) y
  `MOUSEPAD`. Es un campo nuevo con valor por defecto, así que los documentos
  existentes se leen igual y **la versión de la configuración no cambia**.
- Una sesión mouse pad usa su host (autenticación, clave de host, salto) y los
  campos de organización (nombre, grupo, etiquetas, marcador y color). La
  ruta inicial, los scripts, los túneles, el nivel de resiliencia y la
  apariencia del terminal no aplican: el editor los oculta, pero no los borra,
  y cambiar de tipo no pierde nada.
- Guarda dos ajustes propios: la velocidad del puntero y la dirección del
  scroll (natural o clásica).
- Se ofrece en todas las plataformas. La interfaz está pensada para pantallas
  táctiles, pero también funciona con un ratón.

### 2. Conexión y modo `--input`

- La pestaña conecta como cualquier sesión, se asegura de que el agente está
  instalado (con cualquier nivel de resiliencia) y hace `exec` de
  `titan-agent --input`. Si el agente no se puede instalar, la pestaña lo dice
  y no hay mouse pad.
- El protocolo usa el mismo encuadre que el del nivel 3 (tipo de 1 byte,
  longitud y carga), con tipos nuevos:
  - de la app al agente: movimiento relativo del puntero, botón (pulsar o
    soltar: izquierdo, derecho y central), scroll vertical y horizontal en
    fracciones de muesca, texto UTF-8 y tecla con modificadores (pulsar,
    soltar o las dos);
  - del agente a la app: listo para inyectar, o `BYE` con el motivo del
    contrato `TITAN_AGENT_ERROR`.
  La disposición de los bytes se fija en `protocol.go` y `AgentProtocol.kt`,
  que se mantienen en sincronía. Las teclas son un catálogo propio del
  protocolo (no códigos virtuales de Windows ni keysyms de X11), para que cada
  inyector las traduzca.
- No hay ring buffer, offsets ni replay. Lo que se envía durante un corte se
  pierde, y al reconectar se sigue con la política de reconexión de siempre.
  La app agrupa los movimientos por fotograma antes de enviarlos.
- Cuando termina una conexión de entrada, también por un corte, el inyector
  **suelta los botones y teclas que quedaron pulsados**, para que un arrastre
  cortado no deje el botón apretado en el destino.

### 3. Ayudante de escritorio en Windows

- **Lanzamiento**: el front `--input` (sesión 0) registra, si no existe, una
  tarea programada del usuario llamada `titan-ssh-desktop` en la raíz del
  Programador de tareas, y la lanza. La tarea se crea desde XML con:
  inicio de sesión interactivo (`InteractiveToken`), sin elevar
  (`LeastPrivilege`), sin límite de tiempo, sin restricciones de batería y sin
  instancias duplicadas (`IgnoreNew`). Su acción es
  `<binario del agente> --desktop`. Se crea al usarse un mouse pad por primera
  vez, no al instalar el agente.
- **Encuentro**: el ayudante toma un candado propio (`desktop.lock`), escucha
  en un puerto aleatorio de `127.0.0.1` y publica `desktop.json` (puerto,
  token, PID, versión, sesión) en el directorio de estado del usuario, como el
  daemon. El front lo lee y se conecta con un preámbulo propio (otra marca,
  como el canal de control). Si no aparece en 10 s, el usuario no tiene
  sesión de escritorio y el front termina con un error nuevo, `E_NO_DESKTOP`.
- **Versiones**: si el ayudante que responde es de otra versión del agente, el
  front lo para, vuelve a registrar la tarea con el binario actual y la lanza.
- **Vida**: el ayudante termina cuando se cierra la sesión de Windows del
  usuario (cerrar sesión, reiniciar o apagar), o cuando el usuario lo quita
  desde el panel. Cerrar la pestaña del mouse pad o un corte de red no lo
  paran: la siguiente conexión lo encuentra vivo. No se cierra por
  inactividad, igual que el daemon
  ([[ADR-0014 Sesiones del agente sin caducidad]]).
- **Inyección**: `SendInput` de `user32.dll` con `golang.org/x/sys/windows`.
  El movimiento se aplica en **posición absoluta** sobre el escritorio virtual
  (posición actual más el desplazamiento), para que la única curva de
  aceleración sea la de la app y se comporte igual en todos los destinos. El
  texto va con `KEYEVENTF_UNICODE` (los caracteres fuera del plano básico, en
  pares sustitutos), sin depender de la distribución del teclado. Las teclas
  especiales y los modificadores van con códigos virtuales.
- **Sin consola**: el binario de Windows se compila con el subsistema gráfico
  (`-H windowsgui`), para que la tarea no abra una ventana de consola en el
  escritorio del usuario. Cuando lo lanza una persona desde una consola
  (`--status`, `--version`), se engancha a la consola de su padre para
  imprimir.
- **Transparencia**: `--status --json` añade el estado del ayudante (tarea
  registrada, binario al que apunta, si corre y en qué sesión). El panel del
  agente lo muestra con una acción para quitarlo, que para el ayudante y borra
  la tarea (`--remove-desktop`). El resto del agente no cambia.

### 4. Seguridad

- El ayudante solo escucha en `127.0.0.1` y exige el token de 32 bytes, que
  vive en el directorio de estado del usuario, protegido como el del daemon.
- Corre con los privilegios normales del usuario. No da nada que una shell SSH
  de ese usuario no pudiera conseguir: podría crear la misma tarea a mano.
- La tarea se ve en el Programador de tareas y en el panel, y se quita desde
  los dos sitios.

### 5. Fuera de esta decisión

- **Linux X11 y Wayland** reutilizan el tipo de sesión, el protocolo y la
  pestaña. Sus inyectores se deciden en [[Mouse pad en destinos Linux X11]] y
  [[Mouse pad en destinos Linux Wayland]]. Hasta entonces, `--input` termina
  con `E_INPUT_UNSUPPORTED` en esos sistemas, y lo mismo en macOS y BSD.
- Varias sesiones de escritorio abiertas a la vez por el mismo usuario: el
  ayudante corre en la que elija el Programador de tareas.
- **Pantalla de bloqueo y escritorio seguro**: un proceso del usuario no
  puede escribir en el escritorio de Winlogon. Hacerlo exigiría un servicio
  como `SYSTEM` instalado por un administrador; queda aparcado en
  [[Desbloqueo del destino Windows con un servicio de sistema]]. Con el PC
  bloqueado, la pestaña lo avisa.

## Alternativas consideradas

- **Entrada de arranque automático** (`HKCU\...\Run`): también sin
  administrador, pero el ayudante correría siempre, se usara o no.
  Descartada por el usuario.
- **Servicio de Windows o `CreateProcessAsUser`** desde la sesión 0: exigen
  administrador o `SYSTEM`. Descartadas.
- **WMI `Win32_Process.Create`**: acceso denegado para un usuario estándar por
  SSH (experimento del 2026-09-23). Descartada.
- **Pasar la entrada por el daemon del nivel 3**: no aporta nada. La entrada no
  necesita historial, y el front llega al ayudante directamente.
- **Un protocolo aparte** (por ejemplo, líneas JSON): reutilizar el encuadre
  existente reutiliza su decodificador y sus tests.
- **Un segundo binario de Windows solo para el ayudante**: duplicaría los
  artefactos de Windows que fija [[ADR-0010 Empaquetado del agente y descarga bajo demanda]].
  Descartada frente a cambiar el subsistema.
- **`conhost.exe --headless`** para esconder la consola: no está documentado.
  Descartada.
- **Movimiento relativo en `SendInput`**: la aceleración de Windows se suma a
  la de la app y el puntero se mueve distinto en cada PC. Descartada.
- **VNC, RDP, Synergy/Input Leap o KDE Connect**: obligan a instalar y
  configurar un servidor y a abrir puertos. No encajan con titan-ssh, que lo
  hace todo sobre la conexión SSH que ya existe.

## Consecuencias

- Positivas: sin puertos nuevos ni administrador; reutiliza la instalación del
  agente, el encuentro loopback y la reconexión; un protocolo neutro que
  sirve para los tres sistemas; la entrada se comporta igual en todos los
  destinos.
- Negativas / compromisos: una huella visible en el destino Windows (la
  tarea), que el panel enseña y quita; no se controlan la pantalla de bloqueo,
  el escritorio seguro (UAC) ni las ventanas elevadas, por UIPI; hace falta que
  el usuario tenga su sesión de escritorio iniciada; el cambio de subsistema
  del binario de Windows obliga a volver a verificar el front, el daemon y
  ConPTY, y a enganchar la CLI a la consola que la lanza.
