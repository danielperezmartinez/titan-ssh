---
Nombre: 'titan-agent daemon en Windows'
Estado: 'Pendiente'
Resumen: 'Subtarea 4 de ADR-0009 (§5): que el daemon sobreviva en Windows al cierre de la sesión SSH, sin administrador. El front comprueba su Job Object (IsProcessInJob por kernel32 + QueryInformationJobObject) y, si el job lo permite, lanza el daemon con CREATE_BREAKAWAY_FROM_JOB | DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP; si el job tiene KILL_ON_JOB_CLOSE sin BREAKAWAY_OK, sale con E_JOB_NO_BREAKAWAY. Une las subtareas 1 (ConPTY) y 3 (TCP loopback) y se verifica de punta a punta con un usuario estándar, en cierre limpio y en corte brusco. El mecanismo ya se validó en el experimento del 2026-09-23; el método de prueba en Windows está por decidir con el usuario.'
Decisiones: 'Implementa §5 (Windows) de [[ADR-0009 Agente de nivel 3 portable a todos los destinos]], con el mecanismo validado en [[Experimento supervivencia de procesos en Win32-OpenSSH]]. Contexto común en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: ['[[titan-agent PTY propio multiplataforma]]', '[[titan-agent punto de encuentro TCP loopback con token]]']
Fecha de creación: 2026-09-23T22:05:00+02:00
Última modificación: 2026-09-23T22:30:00+02:00
---

# titan-agent: daemon en Windows

Parte de [[Nivel 3 portable a todos los destinos]]. Implementa §5 de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. Necesita el
ConPTY de [[titan-agent PTY propio multiplataforma]] y el punto de encuentro de
[[titan-agent punto de encuentro TCP loopback con token]].

## Qué se sabe (del experimento)

Ver el detalle en
[[Experimento supervivencia de procesos en Win32-OpenSSH]]. Resumen útil para
implementar:

- Cada sesión de Win32-OpenSSH va en un Job Object con `flags=0x2800`:
  `KILL_ON_JOB_CLOSE` (al cerrar la sesión mata todo lo que quede dentro) y
  `BREAKAWAY_OK` (permite salir si se pide al crear el proceso).
- Lanzamiento normal (`DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP`) → muere.
  Añadiendo `CREATE_BREAKAWAY_FROM_JOB` → **sobrevive** al cierre limpio y a
  matar el cliente SSH, y queda fuera de cualquier job (`inJob=false`).
- `os/exec` basta para lanzarlo:
  `cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: flags, HideWindow: true}`,
  stdio a `nil`, `Start()` y `Process.Release()`.
- El proceso superviviente **puede crear un ConPTY** después de cerrarse la
  sesión.
- WMI (`Win32_Process.Create`) → "Acceso denegado" para un usuario estándar por
  SSH; tarea programada → "Solo interactivo", no se ejecuta. **No usarlos.**
- Windows no tiene SIGHUP: tras un corte, el job se cierra cuando termina el
  proceso principal de la sesión (el front, al recibir EOF en su stdin), no en el
  instante del corte.
- `tasklist` y `taskkill /im` → "Acceso denegado" desde SSH; terminar el propio
  proceso por PID sí funciona.
- Solo se ha probado `OpenSSH_for_Windows_10.0p2`. El OpenSSH que trae Windows
  (8.x/9.x) no: de ahí la comprobación en tiempo de ejecución.

## Diseño

### Comprobación del job (front, antes de lanzar el daemon)

- `IsProcessInJob(GetCurrentProcess(), 0, &in)` por
  `windows.NewLazySystemDLL("kernel32.dll").NewProc("IsProcessInJob")` (no está
  en `x/sys/windows`).
- Si está en un job: `windows.QueryInformationJobObject(0, windows.JobObjectExtendedLimitInformation, ...)`
  sobre `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`; mirar
  `BasicLimitInformation.LimitFlags`.
- Decisión:
  - No está en un job, o el job no tiene `KILL_ON_JOB_CLOSE` → lanzamiento
    desacoplado normal.
  - Tiene `BREAKAWAY_OK` (`0x0800`) → lanzar con `CREATE_BREAKAWAY_FROM_JOB`.
  - Tiene `SILENT_BREAKAWAY_OK` (`0x1000`) → los hijos ya salen solos; lanzamiento
    normal.
  - `KILL_ON_JOB_CLOSE` (`0x2000`) sin ninguno de los dos →
    `TITAN_AGENT_ERROR E_JOB_NO_BREAKAWAY ...` y salir: el nivel 3 no está
    disponible en ese host. No intentar alternativas que exijan administrador.
- Colocarlo en el fichero de desacople de Windows (hoy `detach_other.go` es un
  no-op; crear `detach_windows.go` y dejar `detach_other.go` para el resto).

### Rutas y detalles de Windows en el agente

- Directorio de estado `%LOCALAPPDATA%\titan-ssh` (subtarea 2).
- Mantener abierto el candado (`LockFileEx`) en el daemon.
- Shell de las sesiones: la de la subtarea 1 (`DefaultShell` de OpenSSH o
  `%COMSPEC%`).
- Entorno de la shell: heredar el del daemon (perfil del usuario).

## Verificación

> **Método de prueba en Windows: por decidir.** El usuario quiere explorar otras
> opciones antes de fijarlo y lo decidirá al llegar a este punto: **preguntarle
> antes de montar nada.** Lo que sí es obligatorio, sea cual sea el método: un
> usuario **estándar** (sin administrador), cierre limpio **y** corte brusco, y
> limpieza completa al terminar. Abajo queda, como una opción ya probada, el
> método del experimento (sshd local + usuario estándar temporal).

### Opción ya probada: sshd local con usuario estándar temporal

1. Recrear el usuario estándar temporal y autorizar una clave dedicada (pasos
   en [[Experimento supervivencia de procesos en Win32-OpenSSH]]). Pedírselo al
   usuario: necesita administrador.
2. Compilar `GOOS=windows GOARCH=amd64` y subir el binario al perfil del usuario
   de prueba (`scp`).
3. Por SSH: front → HELLO → teclear `echo MARCADOR` → ver la salida.
4. **Cierre limpio**: cerrar la sesión, reconectar tras ≥ 15 s → HELLO con el
   mismo id → replay con el marcador y la shell sigue viva.
5. **Corte brusco**: `kill -9` del cliente SSH con una sesión abierta,
   reconectar → igual que el paso 4.
6. Dos fronts a la vez → un solo daemon (candado).
7. `--stop` termina el daemon.
8. Limpieza: `--stop`, borrar binario y `%LOCALAPPDATA%\titan-ssh`, borrar la
   clave. Al usuario: **reiniciar `sshd` o el PC antes de borrar el perfil**
   (sshd lo deja cargado) y después borrar la cuenta.

Opcional, si hay una máquina con el OpenSSH de Windows (característica
opcional): repetir el paso 4 y anotar los flags del job.

## Criterios de finalización

- En Windows, la sesión del agente sobrevive al cierre limpio y al corte brusco
  de la sesión SSH, con un usuario estándar, probado con el método que decida el
  usuario.
- `E_JOB_NO_BREAKAWAY` probado con un test unitario de la función de decisión
  (flags simulados).
- Limpieza completa del entorno de prueba.
