---
Nombre: 'Diagnóstico cuando el nivel 3 no está disponible'
Estado: 'Hecha'
Resumen: 'Subtarea 6 de ADR-0009 (§7), hecha el 2026-09-27. Antes, si el nivel 3 no se podía usar, el cliente degradaba al nivel 2/1 en silencio (AgentDeployer devolvía null y SessionTab abría la shell sin decir nada). Objetivo: que el usuario vea por qué (SO o arquitectura sin binario, sin permiso de ejecución, subida o checksum fallidos, Windows sin breakaway o sin ConPTY, directorio de estado inseguro, candado imposible, systemd que mata los procesos al salir sin linger) y qué puede hacer. Usa el contrato TITAN_AGENT_ERROR del agente por stderr y el código de salida del canal exec, añade los códigos del lado cliente y detecta KillUserProcesses/Linger por loginctl o busctl. Resultado: la franja de estado de la pestaña muestra el nivel efectivo y, debajo, el motivo y qué hacer; el aviso de systemd ofrece activar linger. El daemon envía el motivo en un BYE con payload opcional cuando no puede abrir el PTY. Verificado con tests y de punta a punta contra un sshd en Docker.'
Decisiones: 'Implementa el diagnóstico de §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y la advertencia de §5 sobre KillUserProcesses. El contrato de errores del agente está en [[Nivel 3 portable a todos los destinos]]. La degradación silenciosa actual viene de [[Nivel 3 transporte cliente e integración de resiliencia]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-27T14:00:00+02:00
---

# Diagnóstico cuando el nivel 3 no está disponible

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa el diagnóstico
de §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]].

## Situación de partida (2026-09-23)

- `SessionTab.kt` (≈ línea 247): si la sesión es `ResilienceLevel.AGENT` y hay
  `agentDeployer`, llama a `ensureInstalled(opened)`; si devuelve `null`, sigue
  por la ruta de shell (nivel 2/1) **sin avisar**.
- `AgentDeployer.ensureInstalled` devuelve `String?`: el motivo
  (`AgentInstaller.Result.Unsupported/Failed.reason`) se pierde en
  `agentDeployer()` (`AgentInstaller.kt`).
- Si el front del agente falla después de instalarse, `AgentTransport.run()`
  termina y la pestaña lo trata como caída/salida; el stderr del front no se lee.
- Superficies que ya existen: `TabStatus(phase, detail)` en `SessionTab`
  (`detail` es un texto legible, p. ej. el motivo de un fallo) y
  `SshExecChannel.errors` (stderr) + `close(): Int?` (código de salida).
- La UI solo muestra el nivel elegido en `SessionEditor.kt` (selector
  "base" / "auto-tmux" / "agente"); no hay indicador del nivel efectivo en la
  pestaña.

## Diseño

1. **Motivo tipado**: sustituir el `String?` de `AgentDeployer` por un resultado
   con el motivo (p. ej. `Installed(path, launch)` / `Unavailable(code, detail)`),
   manteniendo `commonMain` libre de código de plataforma.
2. **Errores del agente**: leer `SshExecChannel.errors` en `AgentTransport`;
   si aparece `TITAN_AGENT_ERROR <CÓDIGO> <mensaje>` (contrato en
   [[Nivel 3 portable a todos los destinos]]) o el front termina con código
   distinto de 0 antes del `HELLO_OK`, tratarlo como "nivel 3 no disponible" (no
   como caída de red, para no reintentar en bucle) y degradar.
3. **Códigos del lado cliente** (propuesta): `E_UNSUPPORTED_TARGET` (SO o
   arquitectura sin binario), `E_UPLOAD` (SFTP/exec fallido),
   `E_CHECKSUM`, `E_NOEXEC` (el binario no se puede ejecutar: `noexec`, AppLocker
   o WDAC; se ve como error al lanzarlo), `E_SYSTEMD_KILL` (ver punto 4).
4. **systemd `KillUserProcesses`** (solo Linux, advertencia; no impide el nivel
   3 mientras la sesión SSH siga abierta):
   - `busctl get-property org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager KillUserProcesses`
     → `b true`/`b false` (o leer `KillUserProcesses=` de
     `/etc/systemd/logind.conf` y `logind.conf.d`).
   - `loginctl show-user "$USER" -p Linger` → `Linger=yes`/`no`.
   - Si `KillUserProcesses=yes` y `Linger=no`: avisar de que el daemon (y tmux)
     morirán al cerrar la última sesión, y ofrecer `loginctl enable-linger`
     (suele estar permitido para el propio usuario por polkit; si falla,
     explicarlo).
5. **Presentación**: mostrar el motivo al abrir la pestaña (p. ej. en
   `TabStatus.detail` o un aviso no intrusivo) y el **nivel efectivo** de la
   sesión, con texto en español que diga qué hacer. Consultar las decisiones
   visuales existentes antes de diseñar el aviso.
6. **Sin bucles**: un "no disponible" no debe reintentar la instalación en cada
   reconexión de la misma pestaña; recordar el motivo durante la vida de la
   pestaña.

## Tests

- Unitarios: parseo de `TITAN_AGENT_ERROR`, mapeo de códigos a mensajes,
  decisión "no disponible" frente a "caída" en `AgentTransport` (canal fake con
  stderr y código de salida), parseo de `busctl`/`loginctl`.
- Punta a punta: provocar `E_STATE_DIR` (directorio de estado 0777) y
  `E_UNSUPPORTED_TARGET` (sin binario empaquetado) contra un destino real y ver el
  aviso en la app.

## Criterios de finalización

- Ningún caso de "nivel 3 no disponible" degrada en silencio: el usuario ve el
  motivo y el nivel efectivo.
- Aviso de `KillUserProcesses` sin linger verificado al menos por test unitario
  (y en un Linux con systemd si hay uno disponible).

## Resultado (2026-09-27)

Ningún caso de "nivel 3 no disponible" degrada ya en silencio. Piezas nuevas en
[[AgentDiagnostics]]; el contrato completo de códigos (agente y cliente) está en
[[Nivel 3 portable a todos los destinos]].

- **Motivo tipado**: `AgentDeployer` devuelve un `AgentDeployment`, `Ready(launch,
  aviso)` o `Unavailable(AgentIssue)`. [[AgentInstaller]] marca cada fallo con su
  código: `E_UNSUPPORTED_TARGET`, `E_NO_BINARY` (la app no lleva el binario y la
  descarga falla, algo distinto de un destino sin agente), `E_UPLOAD` y
  `E_CHECKSUM`.
- **Errores del agente**: [[AgentTransport]] lee el stderr del front. Si `run()`
  termina sin `HELLO_OK`, deja el motivo en `unavailable`: la línea
  `TITAN_AGENT_ERROR`, o el código de salida (126/127 → `E_NOEXEC`; otro distinto
  de 0 → `E_AGENT_EXIT` con la primera línea de stderr, que es como se ve
  AppLocker en Windows). Sin código de salida es una caída de red y se trata como
  hasta ahora.
- **Hueco encontrado**: `E_PTY` y `E_NO_CONPTY` se perdían. Cuando el daemon no
  puede abrir el PTY, el front ya está conectado a él y sale bien, así que el
  daemon respondía con un `BYE` vacío. Ahora el `BYE` lleva un payload opcional
  con el motivo (`<CÓDIGO> <mensaje>`). Es compatible en los dos sentidos: nota en
  [[ADR-0008 Diseño del agente de resiliencia nivel 3]] y en [[AgentProtocol]].
  Un `BYE` vacío de un agente anterior se lee como `E_PTY`.
- **Sin bucles**: `SessionTab` guarda el motivo durante la vida de la pestaña.
  Las reconexiones van directas a la shell, sin reinstalar. Si el agente se
  instala pero rechaza la sesión, la pestaña abre la shell **en la misma
  conexión**, sin reconectar. Una pestaña nueva vuelve a intentarlo.
- **systemd**: en destinos Linux, tras instalar, una sonda (`sh -c`, por si la
  shell de login no es POSIX) pregunta `KillUserProcesses` a logind por `busctl`
  (o lo lee de `logind.conf` y sus `.d`) y `Linger` con `loginctl show-user`. Si
  mata y no hay linger, sale el aviso `E_SYSTEMD_KILL`, que no quita el nivel 3.
  La acción **activar linger** ejecuta `loginctl enable-linger` y vuelve a
  sondear. Si falla, dice por qué y que un administrador puede hacerlo con
  `sudo loginctl enable-linger <usuario>`.
- **Presentación**: `SessionTab.resilience` publica el nivel efectivo (`nivel 3 ·
  agente`, `nivel 2 · tmux`/`screen`, `nivel 1`) y el motivo. Para el nivel 2 la
  automatización informa del multiplexor que encuentra (`ShellIo`). La franja de
  estado de [[TerminalView]] añade el nivel tras la fase y, debajo, el aviso con
  `[-]` en `warning` (una sesión degradada es un estado real), el texto en
  `mute`, la acción en `accent` y `[x]` para cerrarlo. Solo usa marcadores y
  colores ya acordados en [[Vocabulario ASCII ampliado y disciplina de color]],
  así que no hace falta una decisión visual nueva.

## Verificación

- Go: tests en Windows y en Linux (Docker, `-race`, `vet`, `gofmt`). El daemon
  responde con `BYE` y motivo cuando la fábrica de PTY falla, con código y sin él.
- Kotlin: suite completa de escritorio en verde (159 tests), y compilan los
  destinos de escritorio y Android. `AgentDiagnosticsTest` cubre el parseo, la
  clasificación, los textos y la decisión de `AgentTransport` entre "no
  disponible" y caída. `SessionTabAgentDegradeTest` cubre la degradación en la
  misma conexión, que no reintente y que el aviso mantenga el nivel 3.
  `AgentInstallerTest` cubre los códigos y la sonda solo en Linux.
- De punta a punta, contra un sshd desechable en Docker con el binario real:
  `AgentDiagnosticsIntegrationTest` (opt-in) provoca `E_STATE_DIR` con el
  directorio de estado en 0777. La pestaña lo explica, baja a nivel 1 y su shell
  funciona. `E_NO_BINARY` también se explica. Con un `loginctl` falso y
  `KillUserProcesses=yes` en `logind.conf`, la sonda por sshj da el aviso y
  `enableLinger()` lo quita (prueba de una vez, no versionada). Los tests de
  integración del agente siguen pasando con el binario nuevo.
- La rama de `busctl` de la sonda, en el host de pruebas (systemd real):
  `kill=b false`, `Linger=no`, así que no hay aviso, que es lo correcto.
- La franja se renderizó fuera de pantalla (`ImageComposeScene`, prueba no
  versionada) a 900 y 420 px de ancho: el aviso degradado, el de systemd con su
  acción y el texto largo en ancho de móvil se leen bien.
- **No verificado**: `E_NO_CONPTY`, `E_JOB_NO_BREAKAWAY` y `E_NOEXEC` en
  destinos reales (no hay un Windows antiguo, ni un sshd que no permita escapar
  del job, ni un home con `noexec` a mano), solo por tests. Tampoco en la app
  instalada: el cambio sale en la siguiente pre-release.
