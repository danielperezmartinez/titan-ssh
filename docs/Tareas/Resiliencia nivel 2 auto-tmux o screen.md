---
Nombre: Resiliencia nivel 2 auto-tmux o screen
Estado: Hecha
Resumen: 'ENTREGADO. Cuando la sesión pide AUTO_MULTIPLEXER y el destino tiene tmux/screen, se envuelve la shell en una sesión con nombre estable (`tmux new-session -A -s titan-<id>` / `screen -xRR`): el proceso sobrevive a la caída y al reconectar se RE-ENGANCHA a la misma sesión (sin reejecutar scripts) en vez de abrir un shell nuevo. Sin multiplexor degrada al nivel 1. Prerrequisito resuelto: el TerminalEmulator ya soporta pantalla alterna (1049), regiones de scroll (DECSTBM) y edición IL/DL/ICH/DCH/ECH, así que tmux/vim/less/htop se ven bien. Verificado headless (TerminalEmulatorTest 19/19, MultiplexerAutomationTest 6/6) y build de ambos targets.'
Decisiones: Sigue [[ADR-0003 Modelo de resiliencia por niveles]]. Se apoya en el nivel 1 de [[Resiliencia de sesión ante microcortes de red]] y en [[Motor de conexión SSH]]. Superficie catalogada en [[TerminalEmulator]] y [[ScriptRunner]].
Bloqueada: []
Fecha de creación: 2026-09-19T16:45:00+02:00
Última modificación: 2026-09-19T17:35:00+02:00
---

# Resiliencia nivel 2: auto-tmux/screen

## Objetivo

Sobre la reconexión de cliente del nivel 1 (que ya evita cerrar la pestaña y
conserva el scrollback, pero abre un shell nuevo tras una caída total y por tanto
pierde el proceso en primer plano remoto), añadir persistencia del proceso remoto
**cuando el destino disponga de un multiplexor de terminal** (tmux o screen).

## Criterios de finalización

- Detectar si el destino tiene tmux/screen disponible. ✅
- Si lo hay (y la sesión lo pide), envolver el shell en una sesión con nombre
  estable por sesión (`tmux new-session -A -s titan-<id>`), de modo que el proceso
  sobreviva a la caída del transporte. ✅
- Al reconectar (nivel 1), re-enganchar a esa sesión del multiplexor en vez de
  abrir un shell limpio, recuperando el proceso en primer plano. ✅
- Configurable por sesión; degradar limpiamente al nivel 1 si el multiplexor no
  está. ✅

## Prerrequisito resuelto: pantalla alterna en el emulador

tmux/screen (y vim/less/htop) usan la **pantalla alterna** (`?1049h`) y las
**regiones de scroll** (`DECSTBM`), que el [[TerminalEmulator]] ignoraba en su v1
(estaban listadas como fuera de alcance). Sin ese soporte, envolver en tmux se
vería roto (la barra de estado y los redibujados ensuciando el scrollback). Por
eso se abordó primero:

- **Pantalla alterna** (`47`/`1047`/`1049`): buffer separado, sin scrollback
  propio; al salir se restaura la pantalla principal (y el cursor con `1049`).
- **Regiones de scroll** (`DECSTBM`) con `LF`/`RI`/`SU`/`SD` conscientes de la
  región; solo el scroll de pantalla completa (región completa, no alterna)
  alimenta el scrollback.
- **Edición**: `IL`/`DL` (líneas), `ICH`/`DCH` (caracteres), `ECH` (borrar n).
- `resize` redimensiona también la pantalla guardada y resetea la región; `reset`
  (RIS) sale de la alterna. Aún fuera: origin mode (DECOM), tab-stops, charsets.

## Diseño del envoltorio (entregado)

- **`TerminalMultiplexer`** (puro, `terminal`): `detect()` (tmux/screen/none),
  `sessionExists(kind,name)` y `enter(kind,name)`. Las sondas usan un `printf` con
  centinela de token aleatorio cuyo eco del comando no puede casar con el patrón
  (mismo truco que [[ScriptRunner]]). `enter` usa `exec` para que el multiplexor
  **sea** la sesión.
- **Nombre estable**: `titan-<sessionId>` (saneado), igual en la primera conexión
  y en cada reconexión → convergen en la misma sesión del multiplexor.
- **Integración en `StartScriptAutomation`**: si
  `resilienceLevel >= AUTO_MULTIPLEXER`, al conectar detecta y entra; si la sesión
  del multiplexor ya existía re-engancha y **no** reejecuta scripts; si es nueva,
  entra y corre la cadena de arranque dentro. Al reconectar, si el multiplexor
  sobrevivió re-engancha sin replay; si se perdió, cae al replay del nivel 1. Sin
  multiplexor, degrada al nivel 1. (AGENT, nivel 3, degrada de momento a este
  nivel 2 hasta que exista.)

## Verificación

- **Compilación (ambos targets) OK** (`BUILD SUCCESSFUL`):
  `:shared:compileAndroidMain`, `:androidApp:compileDebugKotlin`,
  `:desktopApp:compileKotlin`.
- **Emulador** (`TerminalEmulatorTest`, `:shared:desktopTest`):
  `tests=19 skipped=0 failures=0` (13 previos + 6 nuevos: pantalla alterna aísla y
  restaura, el scroll en alterna no crece el scrollback, la región confina el LF,
  IL/DL en región, ICH/DCH/ECH, SU alimenta scrollback en pantalla completa).
- **Multiplexor** (`MultiplexerAutomationTest`): `tests=6 skipped=0 failures=0`
  (tmux fresco crea+corre scripts, tmux existente re-engancha sin reejecutar, sin
  multiplexor cae a scripts planos, screen cuando solo hay screen, reconexión
  re-engancha sin replay, BASE nunca entra al multiplexor).
- **Suite de escritorio completa**: 16 suites verdes, `failures=0`.
- **No automatizado:** prueba contra un host real con tmux (el auto-wrap y el
  re-enganche tras un corte real). La lógica del envoltorio está cubierta headless
  con un shell falso que responde a las sondas; el render de pantalla completa
  depende del emulador, ya cubierto por `TerminalEmulatorTest`.

## Resultado

Nivel 2 entregado (paquete `im.gar.titanssh.terminal`):

- **`TerminalEmulator`**: soporte de pantalla alterna, regiones de scroll y
  edición de líneas/caracteres (prerrequisito para renderizar apps de pantalla
  completa).
- **`TerminalMultiplexer`** (nuevo): detección + attach-or-create de tmux/screen
  por sesión con nombre estable.
- **`StartScriptAutomation`**: envuelve en multiplexor cuando la sesión lo pide,
  re-engancha sin reejecutar y degrada al nivel 1 sin multiplexor.
- Superficie actualizada en el catálogo: [[TerminalEmulator]] y [[ScriptRunner]].
