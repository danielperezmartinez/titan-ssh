---
Nombre: 'Experimento supervivencia de procesos en Win32-OpenSSH'
Estado: 'Hecha'
Resumen: 'EJECUTADO, resultado positivo. Decide el punto 5 de ADR-0009: un proceso lanzado desde una sesión SSH de Win32-OpenSSH sobrevive al cierre de la sesión SIN administrador si se crea con CREATE_BREAKAWAY_FROM_JOB. El Job Object de la sesión tiene KILL_ON_JOB_CLOSE pero también BREAKAWAY_OK. Probado con un usuario estándar (titantest) contra OpenSSH_for_Windows 10.0p2, en cierre limpio y en corte brusco (kill -9 del cliente); el proceso superviviente crea un ConPTY y lee su salida después del cierre. WMI (Acceso denegado) y la tarea programada (solo interactiva, no se ejecuta) no sirven. No probado el OpenSSH que trae Windows (8.x/9.x), por eso el agente comprobará sus flags de job en tiempo de ejecución. Limpieza completa (cuenta titantest y su perfil borrados tras reiniciar: sshd mantenía cargado el perfil).'
Decisiones: 'Completa [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] (punto 5). Los informes públicos se contradicen según la versión (Win32-OpenSSH #1032, #1642, #1751, #1464), de ahí el experimento en vez de decidir por documentación.'
Bloqueada: []
Fecha de creación: 2026-09-23T21:00:00+02:00
Última modificación: 2026-09-23T22:00:00+02:00
---

# Experimento: supervivencia de procesos en Win32-OpenSSH

## Objetivo

Decidir con datos reales cómo (y si) el daemon de `titan-agent` puede sobrevivir
en Windows al cierre de la sesión SSH que lo lanzó, **sin administrador**, que es
el punto pendiente de
[[ADR-0009 Agente de nivel 3 portable a todos los destinos]].

## Entorno

- `sshd` local del PC de desarrollo: `OpenSSH_for_Windows_10.0p2`
  (`D:\Descargas\OpenSSH-Win64\OpenSSH-Win64`), servicio `sshd` automático.
- `C:\ProgramData\ssh\sshd_config`: solo clave pública
  (`PasswordAuthentication no`), `AuthorizedKeysFile .ssh/authorized_keys`, sin
  bloque `Match Group administrators`.
- Usuario **estándar** de prueba `titantest` (grupo `Usuarios`, no
  administrador), porque el resultado debe valer sin privilegios.
- Clave dedicada `~/.ssh/titan-winspike` (solo para este experimento; se borra
  al terminar).

## Método

Desde una sesión SSH como `titantest`, para cada mecanismo:

1. Lanzar un proceso testigo de larga duración que escribe un latido con marca
   de tiempo en un fichero cada segundo.
2. Cerrar la sesión SSH, esperar ≥ 15 s y reconectar.
3. Comprobar si el proceso sigue vivo y si el latido avanzó durante la
   desconexión.

Mecanismos:

| # | Mecanismo | Qué se prueba |
|---|---|---|
| M0 | Lanzamiento normal (control) | Confirma que la sesión mata a sus hijos. |
| M1 | `CreateProcess` con `CREATE_BREAKAWAY_FROM_JOB` | Si el Job Object de la sesión permite escapar. |
| M2 | WMI `Win32_Process.Create` | Si un proceso creado por el servicio WMI queda fuera del Job Object. |
| M3 | Tarea programada del propio usuario (`schtasks`) | Si un usuario estándar puede crearla y si sobrevive. |

Además, desde el propio proceso, registrar su Job Object (`IsProcessInJob`,
`QueryInformationJobObject`: `KILL_ON_JOB_CLOSE`, `BREAKAWAY_OK`,
`SILENT_BREAKAWAY_OK`). Con el mecanismo ganador, comprobar que el proceso
superviviente **puede crear un ConPTY** con `cmd.exe` y leer su salida.

La herramienta de sondeo es desechable y vive fuera del repositorio (en `%TEMP%`);
en el repo solo queda el resultado.

## Criterios de finalización

- Resultado de M0–M3 anotado en "Resultados" (sobrevive o no, y el Job Object).
- ConPTY verificado en el mecanismo ganador (o anotado por qué no).
- Punto 5 de ADR-0009 **editado** con la decisión: automático (con qué
  mecanismo), opt-in o no soportado.
- Limpieza: usuario `titantest` y su perfil borrados, clave `titan-winspike`
  borrada, ninguna tarea programada ni proceso residual.

## Resultados (2026-09-23)

Ejecutado como `titantest` (integridad **media**, sin administrador) contra
`OpenSSH_for_Windows_10.0p2` en Windows 10.0.19045, con la shell por defecto
`cmd.exe`.

**Job Object de la sesión SSH**: `inJob=true`, `flags=0x2800` →
`KILL_ON_JOB_CLOSE` **sí** y `BREAKAWAY_OK` **sí** (`SILENT_BREAKAWAY_OK` no).
sshd mata todo lo que quede en el job al cerrar la sesión, pero permite que un
proceso escape pidiéndolo de forma explícita.

| # | Mecanismo | Resultado |
|---|---|---|
| M0 | Lanzamiento normal (`DETACHED_PROCESS \| CREATE_NEW_PROCESS_GROUP`) | ❌ Muere al cerrar la sesión: su último latido coincide con el cierre (cierre limpio a las 21:08:52; en el corte brusco, a las 21:11:22, al terminar el proceso principal de la sesión). |
| M1 | Igual + `CREATE_BREAKAWAY_FROM_JOB` | ✅ **Sobrevive**, tanto al cierre limpio como a matar el cliente SSH con `kill -9`. El proceso queda fuera de cualquier job (`inJob=false`). |
| M2 | WMI `Win32_Process.Create` (`Invoke-CimMethod`) | ❌ `Acceso denegado` (`0x80041003`) para un usuario estándar que entra por SSH. |
| M3 | Tarea programada del usuario (`schtasks`) | ❌ Se crea en modo "Solo interactivo" y no llega a ejecutarse (`267011` = `SCHED_S_TASK_HAS_NOT_RUN`). Para que corra sin sesión de escritorio habría que guardar la contraseña. |

**ConPTY**: el testigo M1 creó un ConPTY con `cmd.exe /c echo CONPTY_OK & ver`
y leyó su salida (`CONPTY_OK=true`) **25 s después de cerrarse la sesión SSH**.
El daemon desacoplado puede sostener shells.

**Observación sobre los cortes**: Windows no tiene SIGHUP. Tras un corte brusco,
el job de la sesión no se cierra al caer la conexión, sino cuando termina el
proceso principal de la sesión (en el agente, el front, que termina al recibir
EOF en su stdin). Por eso M0 siguió vivo hasta que acabó el `ping` de la
prueba, y luego murió.

**Parada**: el propio usuario detiene el daemon desacoplado por PID
(`Stop-Process`). `taskkill /im` y `tasklist` devolvieron "Acceso denegado"
desde la sesión SSH, así que el cliente no debe depender de ellos.

**Límite de la verificación**: solo se ha probado `OpenSSH_for_Windows_10.0p2`
(versión de GitHub). El OpenSSH que trae Windows como característica opcional
(versiones 8.x/9.x) **no se ha probado**. Por eso el agente comprueba sus flags
de job en tiempo de ejecución (ver ADR-0009 §5).

**Conclusión**: en Windows, el nivel 3 es **automático, sin administrador**,
lanzando el daemon con `CREATE_BREAKAWAY_FROM_JOB`. WMI y la tarea programada no
sirven sin privilegios.

**Limpieza**: testigos parados, tarea `titan-spike-m3` borrada, ficheros de la
sonda eliminados del perfil de `titantest`, clave `titan-winspike` y carpeta
temporal borradas en el PC. La cuenta `titantest` y su perfil los borró el
usuario. El borrado falló al principio porque el perfil seguía cargado
(`Loaded: True`, probablemente por `sshd` tras el corte brusco provocado); tras
reiniciar el PC se borró sin problema. Comprobado: sin usuario, sin perfil y sin
`C:\Users\titantest`. Si se repite el experimento, reiniciar `sshd` o el PC antes
de borrar el perfil.
