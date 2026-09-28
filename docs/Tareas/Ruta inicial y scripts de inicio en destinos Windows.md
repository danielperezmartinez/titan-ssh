---
Nombre: 'Ruta inicial y scripts de inicio en destinos Windows'
Estado: 'Hecha'
Resumen: 'Al conectar a un Windows (shell cmd.exe o PowerShell), la ruta inicial y los scripts de inicio no se ejecutaban: la automatización escribía sintaxis POSIX (cd -- ''…'' y un centinela printf con __TITAN_…__) terminada en \n, que la consola de Windows no toma como Enter, y todo quedaba pegado en el prompt. Ahora SessionTab sondea la shell del destino una vez por pestaña con un exec (echo %OS% $PSHOME) y ScriptRunner escribe en su sintaxis mediante ShellSyntax: cd /d, set y echo %errorlevel% en cmd; Set-Location, $env: y $? en PowerShell; \r como fin de línea en ambos; y en Windows no se intenta tmux/screen. De paso, un cd inicial sin scripts detrás se envía sin centinela, para no ensuciar la terminal. Verificado con tests y en el emulador contra el sshd de Windows del PC de desarrollo (niveles 1 y 3) y contra el contenedor Linux de pruebas; el usuario lo probó en el emulador y confirma que funciona.'
Decisiones: 'Corrige [[Scripts de inicio por sesión]] y [[Scripts de inicio por sesión sobre el agente]] para los destinos Windows de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. La sonda se hace por exec porque sshd (y titan-agent en Windows) usa para la PTY la misma shell que para los exec. El cuerpo de los scripts se envía tal cual: el usuario lo escribe en el lenguaje de la shell del destino. Superficie en [[ScriptRunner]].'
Bloqueada: []
Fecha de creación: 2026-09-28T07:50:00+02:00
Última modificación: 2026-09-28T09:30:09+02:00
---

# Ruta inicial y scripts de inicio en destinos Windows

## Objetivo

Que una sesión con ruta inicial (y scripts de inicio) funcione igual contra un
destino Windows que contra uno Unix, en todos los niveles de resiliencia. El
usuario lo encontró desde el Pixel: al conectar a un Windows con ruta inicial,
el prompt mostraba `cd -- '<ruta>'printf '%s:%s:%s\n' '__TITAN_…` sin ejecutar.

## Causa

- `ScriptRunner` generaba siempre sintaxis POSIX: `cd -- '<ruta>'`, `export`
  y el centinela `printf '%s:%s:%s\n' '<token>' "$?" '<token>'` con el que
  espera a que cada comando termine (el `__TITAN_…__` que se veía).
- Cada línea acababa en `\n`. Una consola de Windows (ConPTY, tanto la del
  `sshd` de Win32-OpenSSH como la de `titan-agent`) solo toma `\r` como
  Enter, así que las dos líneas quedaban unidas en el prompt sin ejecutarse.
- La detección de multiplexor (nivel 2) también enviaba sondas POSIX.

## Criterios de finalización

- La ruta inicial se aplica en `cmd.exe` y en PowerShell, en los niveles 1 y 3.
- Los scripts de inicio y a demanda llegan con el fin de línea correcto y las
  variables de entorno en la sintaxis de esa shell.
- En Windows no se intenta tmux/screen: el nivel 2 degrada al 1 sin enviar
  sondas.
- Contra un destino Unix se envía exactamente lo mismo que antes.

## Verificación

- `:shared:desktopTest`: 210 tests sin fallos. Nuevos en
  `WindowsShellAutomationTest`: la sonda distingue las tres shells; sintaxis y
  `\r` en cmd y PowerShell (con comillas simples escapadas); un `cd` fallido en
  cmd da `FAILED` con código 1 y aborta el resto; un `cd` solo no imprime
  centinela; una sesión de nivel 2 en Windows informa `NONE` y no envía
  `printf` ni `tmux`. Los falsos de los tests del agente responden a la sonda.
- Comprobado en local: en `cmd`, `cd /d` correcto deja `errorlevel` a 0 y
  fallido a 1; la sonda da `Windows_NT $PSHOME` en cmd, `%OS%` + la ruta de
  PowerShell en PowerShell y `%OS%` en `sh`.
- Emulador `Pixel_9_Pro_XL`, contra el `sshd` de Windows del PC de desarrollo
  (`10.0.2.2:22`, clave hardware) con una ruta inicial en otra unidad
  (`<ruta-de-trabajo>`):
  - APK anterior: reproducido el fallo de la captura del usuario.
  - APK nueva, nivel 1: `cd /d "<ruta-de-trabajo>"` y el prompt queda en esa
    ruta.
  - APK nueva, nivel 3 (agente): igual.
  - Contenedor Linux de pruebas ("proyecto demo"): sin cambios (`cd --`,
    `printf`, el script `POST_INIT` corre).
- 2026-09-28: el usuario lo probó en el emulador y confirma que funciona. La
  clave del emulador se queda autorizada en el destino Windows para futuras
  pruebas.

## Resultado

- Nuevo `ShellSyntax` (`commonMain`, paquete `terminal`): sonda, `cd`,
  `export`, centinela y fin de línea por `RemoteShell` (POSIX, CMD,
  POWERSHELL).
- `ShellIo.remoteShell()` da la shell del destino. `SessionTab` la sondea con
  un `exec` una vez por pestaña (se guarda entre reconexiones; si la sonda
  falla se supone POSIX y se vuelve a preguntar en la siguiente conexión).
- `ScriptRunner` recibe la shell y escribe en su sintaxis;
  `StartScriptAutomation` se la pasa y no entra en el multiplexor si no es
  POSIX.
- Un `cd` inicial sin scripts detrás se envía sin esperar el centinela: solo
  servía para decidir si seguir con los scripts, y en la terminal era ruido.
- Límites: en `cmd` el `%VAR%` de la ruta o de un valor de `envVars` se
  expande (no se puede escapar en la línea interactiva). Una ruta de Windows
  no admite `"`, así que se quita.
