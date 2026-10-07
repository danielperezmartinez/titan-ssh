# titan-agent — agente de resiliencia nivel 3

Binario del destino para el **nivel 3** del modelo de resiliencia de titan-ssh
(ver `docs/Decisiones/ADR-0008 Diseño del agente de resiliencia nivel 3.md` y la
tarea `docs/Tareas/Resiliencia nivel 3 agente propio en el destino.md`).

> **Estado: FUNCIONAL** (núcleo del agente). Protocolo por tramas
> (`internal/protocol`), ring buffer (`internal/buffer`), sesión con PTY real +
> tee vivo + reenganche (`internal/session`) y el binario daemon/front
> (`cmd/titan-agent`) están implementados y **verificados en host real**
> (crea PTY, teea el prompt, aplica input y, al reconectar, el daemon sobrevive y
> reproduce el historial). La instalación versionada con checksum y el
> transporte del lado cliente también están hechos. Se está haciendo portable
> a todos los destinos (ADR-0009): ver **Pendiente**.

## Qué es (ADR-0008, opción B)

El cliente titan-ssh conecta por SSH normal (sshj) y hace `exec` de este binario
sobre el canal ya autenticado — **sin puerto ni auth propios**. Cliente y agente
hablan un protocolo binario por tramas sobre el stdio de ese canal exec. El
agente mantiene un PTY vivo por sesión, guarda su salida en un ring buffer y, al
reconectar, reproduce desde el offset que el cliente confirmó.

Es un **binario nativo estático** (Go), no un agente JVM: no exige Java en el
destino y la distribución multi-arch es trivial. Esto anula la reserva de MINA
SSHD que ADR-0004 había hecho para el agente (el cliente sigue en sshj).

## Estructura

```
agent/
├── go.mod / go.sum
├── cmd/titan-agent/
│   ├── main.go            # modos: front (default) / --daemon / --status / --close-session / --stop
│   │                      #        / --input / --desktop / --remove-desktop (mouse pad) / --desktop-run
│   ├── daemon.go          # candado + listen TCP loopback + serve del protocolo por conexión
│   ├── desktop.go         # mouse pad: ayudante de escritorio, front --input, estado y retirada
│   ├── desktoprun.go      # --desktop-run: petición, validación y registro de los programas abiertos (ADR-0019)
│   ├── desktoprun_windows.go # CreateProcess en el escritorio del usuario, sin elevar
│   ├── desktop_windows.go # tarea programada (schtasks /xml) y copia gráfica del binario
│   ├── desktoptask.go     # XML UTF-16 de la tarea y comillas de la línea de órdenes de Windows
│   ├── pe.go              # copia del ejecutable con el subsistema PE gráfico (ADR-0017)
│   ├── control.go         # canal de control: estado, cerrar una sesión y parada ordenada
│   ├── front.go           # dial-or-spawn del daemon + empalme de stdio; --stop
│   ├── rendezvous.go      # preámbulo con token entre front y daemon (sesión o control)
│   ├── state.go           # agent.json: escritura atómica y validación
│   ├── lock*.go           # candado de instancia única (flock / fcntl / LockFileEx)
│   ├── statedir_*.go      # directorio de estado por sistema y su comprobación
│   ├── errors.go          # códigos TITAN_AGENT_ERROR del front
│   └── detach_*.go        # desacople del daemon: setsid (unix), breakaway del job (windows)
└── internal/
    ├── protocol/          # códec de tramas (espejo de AgentProtocol.kt) + tests; input.go, las del mouse pad
    ├── inject/            # mouse pad: Serve (aplica tramas, suelta lo pulsado) + SendInput en Windows
    ├── buffer/            # ring buffer de salida con offsets + tests
    ├── procmem/           # memoria residente por árbol de procesos (/proc, Toolhelp, ps) + tests
    └── session/           # Registry + Session (PTY, tee vivo, reenganche, cierre) + tests
        ├── pty.go         # PtyError con los códigos E_PTY / E_NO_CONPTY
        ├── pty_unix.go    # PTY Unix común: shell de login, TIOCSWINSZ, EIO → EOF
        ├── pty_{linux,darwin,freebsd}.go  # apertura del master y del esclavo por sistema
        ├── pty_windows.go # ConPTY (CreatePseudoConsole + CreateProcess)
        ├── pty_other.go   # stub (E_PTY) para los sistemas sin backend
        └── pty_contract*_test.go  # tests de contrato contra el PTY real
```

El PTY es un envoltorio propio y fino sobre el sistema (ADR-0009 §4). La única
dependencia del módulo es `golang.org/x/sys`, del proyecto Go; cualquier otra
necesita una ADR. En Unix la shell es `$SHELL -il` (o `/bin/sh -il`); en
Windows, la `DefaultShell` de OpenSSH o `%COMSPEC%`, y ConPTY exige Windows 10
1809 / Server 2019 o posterior (si no, `E_NO_CONPTY`).

El formato de cable es idéntico al del cliente en
`shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt`; **mantener
ambos en sincronía**.

## Modelo de ejecución

`exec titan-agent` corre en modo **front**: empalma su stdio (el canal SSH) al
**daemon**, lanzándolo desacoplado en el primer uso: `setsid` en Unix y, en
Windows, `CREATE_BREAKAWAY_FROM_JOB` para salir del Job Object con el que
Win32-OpenSSH mata los procesos de la sesión al cerrarla. Antes de lanzarlo en
Windows, el front mira los flags de su job: si el job mata al cerrarse y no
deja salir, termina con `E_JOB_NO_BREAKAWAY`. El daemon
sostiene los PTY y el ring buffer por sesión y habla el protocolo por tramas;
sobrevive a la desconexión del front, así que reconectar reengancha por id (el
id viaja en el `HELLO`) y reproduce desde el offset del cliente (ADR-0009 §2-3):

- **Un solo daemon por usuario.** Al arrancar, el daemon toma un candado
  exclusivo no bloqueante sobre `agent.lock` y lo mantiene mientras vive. Si ya
  lo tiene otro, sale con 0 sin tocar nada. El kernel lo suelta al morir el
  proceso, también con `kill -9`.
- **Front y daemon se encuentran por TCP en `127.0.0.1`.** El daemon escucha en
  un puerto aleatorio, genera un token de 32 bytes y, ya escuchando, publica
  `{schema, agent, port, token, pid}` en `agent.json` (fichero temporal +
  `rename`). El front lee el fichero, conecta y los dos extremos demuestran
  que conocen el token sin enviarlo (ADR-0018): el front manda la marca
  (`TTNAGNT2` para una sesión) y un nonce; el daemon, su nonce y un
  HMAC-SHA256 del token sobre la marca y los dos nonces; el front lo comprueba
  antes de enviar nada más y responde con su propio HMAC. Si el apretón no se
  completa en 3 s, el daemon cierra la conexión sin responder. El protocolo
  cliente-agente no cambia: el token nunca sale del destino. El apretón
  anterior, que enviaba el token tal cual, se retiró en `0.1.0-beta.14`: un
  agente de `0.1.0-beta.10` o anterior que siga en marcha aparece como uno
  que no responde y hay que cerrarlo a mano.
- Si no hay fichero, o nadie responde en su puerto, el front lanza un daemon
  (solo si el candado está libre) y vuelve a leer el fichero hasta 10 s.

### Ficheros de estado

En el directorio de estado del usuario, que se crea `0700` y se rechaza si no
es un directorio real del usuario sin acceso de grupo ni otros:

| Sistema | Directorio |
|---|---|
| Unix | `$XDG_STATE_HOME/titan-ssh` o `~/.local/state/titan-ssh` |
| Windows | `%LOCALAPPDATA%\titan-ssh` |

Contiene `agent.lock` (el candado) y `agent.json` (`0600`, con el token). No
se usa `/run/user` (systemd lo borra al cerrar la última sesión) ni `/tmp`.
`--state-dir` cambia el directorio (tests). Si `agent.json` desaparece o se
sobrescribe, el daemon, que tiene el candado, lo vuelve a escribir en unos
segundos: nunca se queda con el candado y sin forma de alcanzarlo.

### Sesiones sin caducidad y control del usuario

Las sesiones **no caducan** (ADR-0014): una sesión solo termina cuando su shell
sale o cuando el usuario la cierra, y el daemon no se cierra solo. Para verlo y
cerrarlo, el mismo binario tiene órdenes que hablan con el daemon por un
**canal de control**: el mismo encuentro loopback + token con otra marca de
preámbulo (`TTNACTL2`), una línea JSON de petición y una de respuesta. El
protocolo cliente-agente no cambia.

- `--status` muestra el daemon y sus sesiones; con `--json`, el informe que lee
  la app (`schema` 1): `state` (`running`, `stopped` o `unreachable` si tiene
  el candado y no responde, como un daemon de `0.1.0-beta.10` o anterior),
  versión del daemon y de la CLI, PID, sistema, hora de arranque,
  memoria del daemon con todo lo que corre debajo y, por sesión, id, creación,
  último uso, desde cuándo no tiene cliente, clientes, historial en bytes y
  memoria de su shell con sus descendientes. Las horas son milisegundos Unix
  del reloj del destino, junto a `nowMs` para compararlas.
- `--close-session <id>` cierra una sesión; sin daemon no hay nada que cerrar
  y no es un error.
- `--stop` pide una parada ordenada: el daemon cierra todas las sesiones, suelta
  el candado y borra `agent.json`. Con un daemon anterior al canal de control,
  se autentica contra `agent.json` (así el PID que mata es seguro el del
  daemon) y lo mata.

La memoria se mide por árbol de procesos: `/proc` en Linux, Toolhelp en
Windows y `ps` en macOS y FreeBSD; en otros sistemas se omite.

### Mouse pad (ADR-0016, ADR-0017)

Una sesión mouse pad hace `exec` de `titan-agent --input`. Ese front no habla
con el daemon, sino con el **ayudante de escritorio** del usuario
(`--desktop`), el único proceso que puede inyectar ratón y teclado, porque
corre en la sesión de escritorio del usuario y no en la de sshd (la sesión 0
en Windows). Las tramas de entrada (`internal/protocol/input.go`) no tienen
offsets ni replay. Cuando se cierra una conexión, `inject.Serve` suelta los
botones y teclas que quedaran pulsados.

- **Encuentro**: el mismo esquema que el daemon, con otros ficheros
  (`desktop.lock`, `desktop.json`) y otras marcas (`TTNADSK2` para la
  entrada, `TTNADCT2` para el control: `status` y `stop`; y `TTNADRN2` para
  `--desktop-run`, más abajo).
- **Windows**: si no responde ningún ayudante, el front escribe
  `desktop-<versión>.exe` en el directorio de estado. Es una copia de sí
  mismo con el subsistema PE gráfico, para que no abra una consola. Después
  registra (`schtasks /create /xml`, en UTF-16) la tarea
  `titan-ssh-desktop-<usuario>`, que solo se ejecuta con el usuario conectado
  y sin elevar, y la lanza. Windows la arranca en el escritorio del usuario,
  también si es de escritorio remoto. Si el ayudante que responde es de otra
  versión, se para y se relanza.
- **Inyección**: `SendInput`. El movimiento se aplica en posición absoluta,
  sin la aceleración de Windows, y encadena las ráfagas sobre su propio
  destino, porque `GetCursorPos` va por detrás. El texto va como Unicode. El
  bloqueo se detecta porque `OpenInputDesktop` falla con la pantalla de
  bloqueo, UAC o Ctrl+Alt+Supr delante, y se avisa con `INPUT_READY`.
- **Vida y control**: el ayudante no se cierra solo. Termina al cerrar la
  sesión de Windows o con `--remove-desktop`, que además borra la tarea y las
  copias. `--status --json` lo incluye en `desktop`.
- Fuera de Windows, `--input` termina con `E_INPUT_UNSUPPORTED` (X11 y
  Wayland tienen sus tareas).

### Programas en el escritorio del usuario (ADR-0019)

`titan-agent --desktop-run [--cwd <dir>] <programa> [argumentos…]` abre un
programa en el escritorio del usuario desde una shell que no lo tiene (la
sesión 0 de sshd en Windows) e imprime su PID. Cada shell de una sesión del
agente tiene la ruta del binario en `TITAN_AGENT`; por ejemplo, en
PowerShell: `& $env:TITAN_AGENT --desktop-run emulator -avd Pixel_9_Pro_XL`.

- **Front**: resuelve el programa como la shell que llama (su `PATH`, nunca
  el directorio actual por accidente) y usa su directorio actual si no se da
  `--cwd`. Encuentra o arranca el ayudante igual que `--input`, y le manda una
  línea JSON por una conexión `TTNADRN2`. Esa marca solo existe en el apretón
  de manos con HMAC: no tiene variante antigua, y el canal de control no
  acepta la orden.
- **Ayudante**: valida la petición (programa y directorio absolutos y
  existentes, sin NUL, sin `.bat` ni `.cmd`, que Windows pasaría a
  `cmd.exe`) y lanza con `CreateProcess` y su propio token, que no está
  elevado. No usa `ShellExecute` ni una shell, así que un programa que pide
  administrador falla con un error y nunca aparece un aviso de UAC. Abre las
  ventanas en modo normal y da una consola propia a los programas de
  consola. Si su tarea corre en un job que lo permite, el programa sale de
  él, así que quitar el ayudante no lo cierra.
- **Registro**: cada petición, abierta o rechazada, va a `desktop-run.log`
  en el directorio de estado (una línea JSON; se recorta a los 100 últimos
  al pasar de 200). `--status --json` da los 10 últimos en `desktop.runs`, y
  `--remove-desktop` borra el registro con todo lo demás.
- Fuera de Windows termina con `E_INPUT_UNSUPPORTED`.

### Errores

Si el front no puede dar servicio, termina con código distinto de 0 y una línea
en stderr: `TITAN_AGENT_ERROR <código> <mensaje>`. Códigos de este binario:
`E_STATE_DIR`, `E_LOCK`, `E_DAEMON_START`, `E_AUTH` y, en Windows,
`E_JOB_NO_BREAKAWAY` (tabla completa en la
tarea `Nivel 3 portable a todos los destinos`). Los del mouse pad:
`E_INPUT_UNSUPPORTED`, `E_NO_DESKTOP` (no arrancó el ayudante: nadie tiene la
sesión iniciada, o el ayudante dejó su error en `desktop-error.txt`) y
`E_DESKTOP_TASK` (no se pudo crear o lanzar la tarea).

## Build / test

```sh
cd agent
go test ./...               # protocolo, buffer, procmem, session (PTY real y fake), front/daemon, control y mouse pad
TITAN_INJECT_LIVE=1 go test -run TestLiveMoveIsExact ./internal/inject/  # Windows: mueve el cursor real
# binarios multi-arch (estáticos, ~2.5 MB):
GOOS=linux  GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-amd64 ./cmd/titan-agent
GOOS=linux  GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/titan-agent-linux-arm64 ./cmd/titan-agent
```

## Pendiente

Lo que falta del nivel 3 portable (ADR-0009) está en la tarea
`docs/Tareas/Nivel 3 portable a todos los destinos.md`: la instalación en
destinos Windows y multi-SO, y el diagnóstico cuando el nivel 3 no está
disponible.

- Mejora menor: capar el buffer por el mínimo de los `ACK` (hoy capa por bytes).

## Licencia

Copyright (C) 2026 Daniel Pérez Martínez. Forma parte de titan-ssh y se
distribuye bajo la licencia **GPL-3.0-or-later**, sin ninguna garantía; ver
[`LICENSE`](../LICENSE) y los avisos de terceros en
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
