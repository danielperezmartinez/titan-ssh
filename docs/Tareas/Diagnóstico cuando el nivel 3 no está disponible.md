---
Nombre: 'Diagnóstico cuando el nivel 3 no está disponible'
Estado: 'Pendiente'
Resumen: 'Subtarea 6 de ADR-0009 (§7): hoy, si el nivel 3 no se puede usar, el cliente degrada al nivel 2/1 en silencio (AgentDeployer devuelve null y SessionTab abre la shell sin decir nada). Objetivo: que el usuario vea por qué (SO o arquitectura sin binario, sin permiso de ejecución, subida o checksum fallidos, Windows sin breakaway o sin ConPTY, directorio de estado inseguro, candado imposible, systemd que mata los procesos al salir sin linger) y qué puede hacer. Usa el contrato TITAN_AGENT_ERROR del agente por stderr y el código de salida del canal exec, añade los códigos del lado cliente y detecta KillUserProcesses/Linger por loginctl o busctl.'
Decisiones: 'Implementa el diagnóstico de §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y la advertencia de §5 sobre KillUserProcesses. El contrato de errores del agente está en [[Nivel 3 portable a todos los destinos]]. La degradación silenciosa actual viene de [[Nivel 3 transporte cliente e integración de resiliencia]].'
Bloqueada: ['[[titan-agent punto de encuentro TCP loopback con token]]', '[[titan-agent daemon en Windows]]', '[[Instalación del agente en destinos Windows y multi-SO]]']
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:05:00+02:00
---

# Diagnóstico cuando el nivel 3 no está disponible

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa el diagnóstico
de §7 de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]].

## Situación actual

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
