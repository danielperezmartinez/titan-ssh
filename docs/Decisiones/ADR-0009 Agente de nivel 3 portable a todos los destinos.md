---
Nombre: 'Agente de nivel 3 portable a todos los destinos'
Número: 9
Estado: 'Aceptada'
Resumen: 'Rediseño del agente titan-agent (ADR-0008) para que el nivel 3 funcione en todos los destinos habituales (Linux de cualquier distro y libc, macOS, FreeBSD, Windows 10 1809+/Server 2019+) y degrade al nivel 2/1 con diagnóstico en el resto. Se mantiene Go y el protocolo cliente-agente. Cambia lo que ataba el agente a Unix: el front y el daemon se encuentran por TCP de loopback con token secreto (fichero de estado privado en el perfil del usuario) en vez de un socket Unix en /run/user o /tmp; una instancia única por usuario con candado del SO (flock / fcntl / LockFileEx); y un envoltorio de PTY propio y fino (PTY Unix por sistema y ConPTY en Windows), sin librerías de terceros. En Windows el daemon sobrevive al cierre de la sesión SSH, sin administrador, creándolo con CREATE_BREAKAWAY_FROM_JOB (verificado por experimento con un usuario estándar en OpenSSH_for_Windows 10.0p2; el agente comprueba en tiempo de ejecución que su job lo permite y, si no, degrada con diagnóstico).'
Decisión: 'Mantener Go y el protocolo por tramas de ADR-0008. Sustituir el socket Unix por TCP 127.0.0.1 en puerto aleatorio + token de 256 bits en un fichero de estado privado del usuario; garantizar un único daemon por usuario con un candado del SO que el kernel libera al morir el proceso; implementar el PTY con un envoltorio propio (stdlib syscall + golang.org/x/sys, sin creack/pty ni otras librerías de terceros), con backend Unix por sistema y ConPTY en Windows; ampliar los destinos compilados a todos los que Go soporta de forma nativa; y adaptar la instalación del cliente (detección de SO sin uname, rutas y comillas de Windows). Desacople: setsid en Unix y CREATE_BREAKAWAY_FROM_JOB en Windows, con comprobación en tiempo de ejecución de los flags del Job Object.'
Consecuencias: 'Nivel 3 en Windows, macOS, BSD y cualquier Linux, sin depender de systemd, de /run/user ni de /tmp; desaparece la carrera de dos daemons y la pérdida de sesiones cuando systemd borra el directorio de ejecución al cerrar sesión. A cambio, titan mantiene código propio de PTY por sistema (varios cientos de líneas) y pruebas en cada SO (macOS no disponible hoy en el entorno), abre un puerto de loopback (protegido por token), y el tamaño de los binarios empaquetados crece con el número de destinos. En Windows depende de que el Job Object de sshd permita BREAKAWAY_OK: verificado en OpenSSH_for_Windows 10.0p2, no en el OpenSSH que trae Windows (8.x/9.x); donde no lo permita, el nivel 3 no está disponible y se degrada con diagnóstico.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-23T20:55:00+02:00
Última modificación: 2026-09-23T22:10:00+02:00
---

# ADR-0009 · Agente de nivel 3 portable a todos los destinos

> **Estado: Aceptada (2026-09-23).** El punto 5 (desacople en Windows) se
> resolvió con el experimento
> [[Experimento supervivencia de procesos en Win32-OpenSSH]]. Sustituye en
> [[ADR-0008 Diseño del agente de resiliencia nivel 3]] sus §5 (destinos) y §6
> (ciclo de vida: punto de encuentro y daemonización), además de la elección de
> `creack/pty`. El resto de ADR-0008 (opción B sobre `exec`, Go, protocolo por
> tramas, semántica de buffer y replay, instalación por SFTP con checksum) sigue
> vigente; por eso ADR-0008 no se marca `Reemplazada` y solo lleva una nota de
> enlace, igual que se hizo con ADR-0004. Desde aquí, esta ADR no se edita para
> cambiar la decisión.

## Contexto

El producto es multiplataforma y conecta a **ordenadores y servidores propios**
(ver [[README]]): los destinos del nivel 3 incluyen Linux, macOS y **Windows**
(OpenSSH Server). El agente entregado bajo
[[ADR-0008 Diseño del agente de resiliencia nivel 3]] solo funciona en Unix y
solo se compila para `linux/amd64`, `linux/arm64` y `darwin/arm64`. Al revisar
el daemon ([[titan-agent fuga previa al HELLO y permisos del socket]]) aparecieron
los límites del diseño:

| Pieza | Diseño actual | Problema |
|---|---|---|
| PTY | `creack/pty` (tercero) | Solo Unix; en Windows hace falta ConPTY. |
| Punto de encuentro front↔daemon | Socket Unix en `$XDG_RUNTIME_DIR` o `/tmp/titan-ssh-<uid>` | En Linux con systemd, `/run/user/<uid>` **se borra al cerrar la última sesión**: el socket desaparece, el daemon queda huérfano y la reconexión arranca otro, **perdiendo las sesiones**. En macOS la ruta de un socket está limitada a 104 bytes. En Windows el modelo de permisos es distinto. |
| Instancia única | Ninguna | Dos fronts simultáneos arrancan dos daemons; el segundo borra el socket del primero y lo deja huérfano con sus sesiones. |
| Desacople | `setsid` | En Windows, Win32-OpenSSH mete los procesos de la sesión en un Job Object que los mata al cerrarla. |
| Cliente | `uname`, `~/.local/...`, comillas de `sh` | No funciona contra un destino Windows. |

Garantía realista: **ningún** diseño, en ningún lenguaje, puede ejecutar un
proceso persistente en todos los hosts (`noexec` en home y `/tmp`,
AppLocker/WDAC, shells restringidas o `ForceCommand`, SFTP desactivado,
políticas que matan los procesos del usuario al salir). La garantía de
"funciona siempre" la dan los niveles de [[ADR-0003 Modelo de resiliencia por niveles]]:
el nivel 1 funciona en cualquier destino. Esta ADR amplía **dónde llega el nivel 3**
y exige que, donde no llegue, se degrade con un diagnóstico claro.

## Decisión

### 1. Lenguaje: se mantiene Go

Go compila de forma nativa y estática (`CGO_ENABLED=0`) para prácticamente
todos los destinos desde cualquier máquina de desarrollo, incluida la de
Windows. Las limitaciones anteriores **son del diseño, no del lenguaje**; el
problema de Windows (Job Object) es del sistema operativo y ningún lenguaje lo
evita. Ver "Alternativas".

### 2. Punto de encuentro: TCP de loopback + token secreto

- El daemon escucha **solo en `127.0.0.1`**, en un **puerto aleatorio** (puerto 0).
- Genera un **token de 32 bytes** con `crypto/rand`.
- Escribe `{versión, puerto, token, pid}` en un **fichero de estado privado** del
  usuario, de forma atómica (fichero temporal + `rename`) y solo después de
  estar escuchando:
  - Unix: `~/.local/state/titan-ssh/agent.json` (o `$XDG_STATE_HOME`),
    directorio `0700` y fichero `0600`. Se reutilizan las comprobaciones de
    [[titan-agent fuga previa al HELLO y permisos del socket]]: directorio
    real, del usuario y sin acceso de grupo u otros.
  - Windows: `%LOCALAPPDATA%\titan-ssh\agent.json` (el perfil es privado del
    usuario por ACL). Si hace falta, se fija una DACL explícita solo para el
    usuario.
- El **front** lee el fichero, conecta al puerto y envía un **preámbulo**
  (marca fija + token) antes de empalmar los bytes. El daemon compara el token en
  tiempo constante (`crypto/subtle`) y cierra la conexión si no coincide.
- **El protocolo cliente↔agente de [[AgentProtocol]] no cambia.** El preámbulo
  solo existe en el tramo front↔daemon, dentro del host: el token nunca sale del
  destino ni viaja por SSH.

Por qué:
- Es el **mismo código en todos los sistemas**.
- El estado vive en el perfil del usuario, que **sobrevive al cierre de sesión**
  (no en `/run/user`) y no lo borran los limpiadores de `/tmp`.
- Elimina el límite de ruta de macOS.
- Otro usuario local puede llegar al puerto, pero sin el token no puede hacer
  nada. Es el modelo de *connection token* que usan servidores remotos de
  editores.

### 3. Instancia única por usuario: candado del sistema operativo

- Antes de escuchar, el daemon toma un **candado exclusivo no bloqueante** sobre
  `agent.lock`, en el directorio de estado, y lo mantiene mientras vive.
- **Si no lo consigue**, ya hay un daemon vivo: sale con código 0 y el front
  conecta con el existente.
- Solo quien tiene el candado reescribe el fichero de estado.
- El kernel libera el candado al morir el proceso (también con `kill -9` o un
  fallo): no hay candados huérfanos, ficheros PID ni heurísticas.
- Envoltorio **propio y fino**, sin terceros:
  - `flock(2)` en Linux, macOS y BSD;
  - `fcntl(F_SETLK)` en AIX y Solaris;
  - `LockFileEx` en Windows.
- Riesgo conocido: los candados sobre **home en NFS** dependen del servidor NFS.
  Si falla el candado, se degrada con diagnóstico.

### 4. PTY: envoltorio propio y fino, sin librerías de terceros

Se retira `creack/pty`. El seam `session.Pty` (`Read`/`Write`/`Resize`/`Close`)
ya existe y se mantiene; cambia la implementación:

- **Unix**, un fichero por sistema, solo con `syscall` y `golang.org/x/sys/unix`:
  - Linux: `/dev/ptmx` + `TIOCSPTLCK`/`TIOCGPTN`;
  - macOS: `TIOCPTYGRANT`/`TIOCPTYUNLK`/`TIOCPTYGNAME`;
  - FreeBSD: `posix_openpt` + `TIOCGPTN`;
  - redimensionado con `TIOCSWINSZ`;
  - el hijo arranca con `SysProcAttr{Setsid, Setctty}` de la stdlib.
- **Windows**, ConPTY con `golang.org/x/sys/windows`:
  - `CreatePseudoConsole`/`ResizePseudoConsole`/`ClosePseudoConsole` y
    `NewProcThreadAttributeList` con `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE`;
  - `CreateProcess` directo, porque `os/exec` no admite el atributo;
  - requiere **Windows 10 1809 / Windows Server 2019** o posterior;
  - en versiones anteriores, degradar.
- **Shell por defecto**: en Unix, `$SHELL -il` (como hoy). En Windows, la shell
  configurada para OpenSSH (`HKLM\SOFTWARE\OpenSSH\DefaultShell`) o, si no hay,
  `%COMSPEC%`. Se afina en la implementación.
- `golang.org/x/sys` es un módulo **del proyecto Go** (lo mantiene el equipo de
  Go; la stdlib `syscall` está congelada y no expone ConPTY ni `LockFileEx`). Se
  acepta como única dependencia y **no se considera de terceros**. Cualquier
  otra dependencia del agente requiere una ADR.
- **Tests de contrato** comunes a todas las implementaciones (eco, redimensionado,
  EOF al salir la shell, cierre), ejecutados en cada sistema: Linux en contenedor
  Docker `golang:1.27` (también para `go test -race`), Windows en local; macOS pendiente de
  disponer de un Mac o de un runner de CI con macOS.

### 5. Desacople del daemon de la sesión SSH

- **Unix**: `setsid` al lanzar el daemon (como hoy). Límite: en Linux con
  systemd y `KillUserProcesses=yes`, systemd mata todos los procesos del usuario
  al salir salvo que tenga *linger*. Afecta igual a tmux (nivel 2). El cliente lo
  **detecta y lo explica**, con la opción `loginctl enable-linger` si la política
  lo permite.
- **Windows**: el front lanza el daemon con
  `CREATE_BREAKAWAY_FROM_JOB | DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`.
  - **Por qué funciona**: Win32-OpenSSH mete cada sesión en un Job Object con
    `KILL_ON_JOB_CLOSE` (al cerrarse la sesión mata todo lo que quede dentro),
    pero con `BREAKAWAY_OK`, que permite a un proceso salir del job pidiéndolo de
    forma explícita. El daemon queda fuera de cualquier job y sus shells
    (ConPTY), que crea él mismo, también.
  - **Verificado** en [[Experimento supervivencia de procesos en Win32-OpenSSH]]
    con un usuario **estándar** (sin administrador) contra
    `OpenSSH_for_Windows_10.0p2`: sobrevive al cierre limpio y a un corte brusco
    del cliente, y crea un ConPTY después de cerrarse la sesión.
  - **Descartados por el experimento**: WMI `Win32_Process.Create` (acceso
    denegado para un usuario estándar por SSH) y la tarea programada del usuario
    (solo interactiva: no se ejecuta sin sesión de escritorio).
  - **Comprobación en tiempo de ejecución**: el OpenSSH que trae Windows
    (8.x/9.x) no se ha probado. Antes de lanzar el daemon, el front consulta su
    job (`IsProcessInJob` + `QueryInformationJobObject`): si está en un job con
    `KILL_ON_JOB_CLOSE` y sin `BREAKAWAY_OK`, el nivel 3 no está disponible en
    ese host y se degrada con diagnóstico, sin intentar alternativas que exijan
    administrador.
  - **Cortes de red**: Windows no tiene SIGHUP; el job de la sesión se cierra
    cuando termina su proceso principal (el front, al recibir EOF en su stdin),
    no en el instante del corte. No afecta al diseño: el daemon ya está fuera
    del job.
  - **Parada del daemon**: por PID desde el propio usuario (`Stop-Process`);
    `taskkill /im` y `tasklist` dan "Acceso denegado" desde una sesión SSH, así
    que el cliente no debe depender de ellos.
  - `IsProcessInJob` no está en `golang.org/x/sys/windows`; se llama por
    `kernel32` con `NewLazySystemDLL`, dentro del mismo envoltorio propio.

### 6. Destinos compilados

Todos los que Go soporta de forma nativa y estática:
- `linux/{amd64, arm64, arm (v7), 386, riscv64, ppc64le, s390x}`;
- `darwin/{amd64, arm64}`;
- `windows/{amd64, arm64}`;
- `freebsd/{amd64, arm64}`.

Los mínimos de SO son los de la versión de Go usada (go.dev/wiki/MinimumRequirements),
más Windows 10 1809 por ConPTY. El **empaquetado** de tantos binarios (unos
2,5 MB cada uno) se resuelve en la tarea de distribución. La propuesta es
empaquetar los destinos principales y descargar el resto bajo demanda con el
SHA-256 fijado en la app.

> Decidido por el usuario el 2026-09-23 y recogido en
> [[ADR-0010 Empaquetado del agente y descarga bajo demanda]].

### 7. Cliente

- **Detección de SO/arquitectura sin depender de `uname`**. Por ejemplo, la ruta
  que devuelve SFTP para `.` (`/C:/Users/...` delata Windows) y después una sonda
  específica del sistema.
- **Rutas de instalación** por sistema (`~/.local/share/titan-ssh/`,
  `%LOCALAPPDATA%\titan-ssh\`) y **comillas del comando `exec`** según la shell
  del destino (`sh`, `cmd`, PowerShell).
- **Diagnóstico** cuando no se puede usar el nivel 3 (sin permiso de ejecución,
  SO o arquitectura sin binario, ConPTY ausente, `KillUserProcesses` sin
  *linger*, candado imposible), en vez de degradar en silencio.

### Qué se conserva y qué cambia del código actual

- **Se conserva**: el protocolo y su códec (Go y Kotlin); el ring buffer; la
  lógica de `Session`/`Registry`; el arreglo de la fuga previa al HELLO y el
  plazo del HELLO; las comprobaciones de dueño y permisos del directorio
  (pasan al directorio de estado).
- **Cambia**: `creack/pty` → envoltorio propio; socket Unix + `listenPrivate`
  (umask) → TCP de loopback + token + fichero de estado; se añade el candado de
  instancia única; lista de destinos; instalación del cliente.

## Alternativas consideradas

- **Go con un canal de comunicación por sistema** (socket Unix, socket abstracto
  de Linux, *named pipe* de Windows): funciona, pero son tres caminos con tres
  modelos de seguridad que mantener. Descartada frente al TCP de loopback, que es
  uno solo.
- **Reescribir en Rust** (`portable-pty`, `interprocess`): buenas librerías y
  binarios menores, pero compilar para macOS desde Windows exige zig o el SDK de
  Apple, obliga a reescribir el agente y **no resuelve el Job Object**.
  Descartada.
- **Kotlin/Native**: permitiría compartir el códec con el cliente (se eliminaría
  la duplicación Kotlin/Go), pero los binarios de macOS solo se compilan en un
  Mac y cubre menos destinos (sin musl ni varias arquitecturas). Descartada.
- **Scripts (sh/Python/Perl)**: `sh` no puede crear un PTY, y Python/Perl no
  están garantizados, sobre todo en Windows. Descartada.
- **Librerías de terceros para PTY o candado** (`creack/pty`, `go-pty`,
  `gofrs/flock`): descartadas por principio, para no depender de terceros en una
  pieza crítica. El código propio equivalente es pequeño y acotado.
- **Seguir con el socket Unix y endurecerlo**: no resuelve Windows ni el borrado
  de `/run/user`, que pierde las sesiones justo en el caso que el nivel 3 debe
  cubrir.

## Consecuencias

- **Positivas**: nivel 3 en Windows, macOS, BSD y cualquier Linux; independencia
  de systemd, `/run/user` y `/tmp`; desaparecen la carrera de dos daemons y la
  pérdida de sesiones al cerrar sesión; un solo camino de comunicación; sin
  dependencias de terceros en el agente.
- **Negativas / compromisos**: código de PTY propio por sistema (varios cientos de
  líneas) con pruebas en cada SO (macOS no disponible hoy); un puerto de loopback
  abierto (protegido por token); binarios empaquetados más pesados; en Windows,
  el nivel 3 depende de que sshd permita `BREAKAWAY_OK` (verificado en
  OpenSSH_for_Windows 10.0p2; en otras versiones se comprueba al arrancar y, si
  no lo permite, se degrada con diagnóstico).

Ver [[ADR-0003 Modelo de resiliencia por niveles]],
[[ADR-0008 Diseño del agente de resiliencia nivel 3]] y
[[Resiliencia nivel 3 agente propio en el destino]].

Implementación: [[Nivel 3 portable a todos los destinos]] (tarea paraguas con
las subtareas y su orden).
