---
Nombre: 'Ayudante de escritorio con una copia gráfica del agente'
Número: 17
Estado: 'Aceptada'
Resumen: 'Sustituye en parte a ADR-0016, solo en el punto Sin consola. Compilar todo el binario de Windows con el subsistema gráfico rompe los destinos cuya shell de SSH es PowerShell: PowerShell (5.1 y 7) espera al programa y le pasa el stdio, pero no recoge su código de salida, ni con exit $LASTEXITCODE, y la app lo usa en --status, --stop, --close-session y en el diagnóstico. En su lugar, el binario del agente sigue siendo de consola, y el front --input escribe en el directorio de estado una copia de sí mismo con el campo Subsystem de la cabecera PE cambiado a gráfico (desktop-<versión>.exe). La tarea programada lanza esa copia, que arranca en el escritorio del usuario sin ventana de consola. Probado el 2026-10-01 por SSH, con cmd, PowerShell y pwsh.'
Decisión: 'Mantener titan-agent como binario de consola en Windows y lanzar el ayudante de escritorio desde una copia derivada en tiempo de ejecución, idéntica salvo por el subsistema PE (gráfico), guardada en el directorio de estado del usuario y apuntada por la tarea programada.'
Consecuencias: 'El front, el daemon, ConPTY y la CLI no cambian, y PowerShell conserva los códigos de salida. No hay artefactos nuevos en el empaquetado de ADR-0010. A cambio, el destino guarda un ejecutable más (unos MB) en el directorio de estado, que el front mantiene al día por versión y que se borra al quitar el ayudante; y si algún día se firma el agente, la copia pierde la firma, porque cambia un byte de la cabecera.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-01T20:10:00+02:00
Última modificación: 2026-10-01T21:30:00+02:00
---

# ADR-0017 · Ayudante de escritorio con una copia gráfica del agente

Sustituye en parte a
[[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]: solo su
punto **Sin consola** (y la consecuencia que lo acompaña). El resto de
ADR-0016 sigue vigente.

## Contexto

ADR-0016 compilaba el binario de Windows con el subsistema gráfico
(`-H windowsgui`) para que la tarea programada no abriera una ventana de
consola en el escritorio del usuario. Al probarlo por SSH (2026-10-01, PC de
pruebas con Win32-OpenSSH 10.0p2), con un binario que copia su stdin a su
stdout y termina con código 3:

| Shell que ejecuta el comando | Binario de consola | Binario gráfico |
|---|---|---|
| `cmd /c` | stdio ✅, código 3 | stdio ✅, código 3 |
| `powershell -c "& '...'"` | stdio ✅, código 1 | stdio ✅, código **0** |
| `powershell -c "& '...'; exit $LASTEXITCODE"` | código 3 | código **0** |
| `pwsh` (7), las dos formas | igual que `powershell` | código **0** |

PowerShell espera al programa gráfico y le pasa el stdio, pero no recoge su
código de salida. La app lo necesita: `AgentControl` comprueba que `--status`,
`--stop` y `--close-session` salen con 0, y el diagnóstico usa el código cuando
el front muere sin escribir la línea `TITAN_AGENT_ERROR`. Los destinos Windows
cuya shell por defecto de OpenSSH es PowerShell, que el instalador ya
soporta, quedarían sin forma de detectar fallos.

En el mismo experimento, una copia del binario de consola con un solo campo
cambiado, el `Subsystem` de la cabecera opcional PE (3 → 2), lanzada por la
tarea programada, arrancó en la sesión del usuario **sin ventana de consola**
(`GetConsoleWindow` = 0) y movió el cursor.

## Decisión

- `titan-agent` sigue siendo un binario de **consola** en todos los Windows.
  Nada cambia en el front, el daemon, ConPTY ni la CLI.
- Antes de registrar o lanzar la tarea, el front `--input` se asegura de que
  existe `desktop-<versión>.exe` en el directorio de estado del usuario
  (`%LOCALAPPDATA%\titan-ssh`). Es una copia de su propio ejecutable con el
  campo `Subsystem` de la cabecera PE puesto a gráfico (2), y nada más
  cambiado. Se escribe en un temporal y se renombra. Si ya existe y es
  idéntica byte a byte, no se toca.
- La tarea programada apunta a esa copia (`desktop-<versión>.exe --desktop`).
- Las copias de otras versiones se borran cuando no están en uso. Quitar el
  ayudante desde el panel borra la tarea y las copias.
- La copia vive en el directorio de estado, que ya se exige privado del
  usuario, y se deriva del binario que la app instaló y verificó por SHA-256.

## Alternativas consideradas

- **Todo el binario con el subsistema gráfico** (ADR-0016): rompe los códigos
  de salida con PowerShell. Sustituida por esta.
- **Un segundo binario gráfico en el empaquetado**: dos artefactos más por
  versión (Windows amd64 y arm64) en la app y en el Release, con sus
  checksums. La copia en tiempo de ejecución da lo mismo sin tocar
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]].
- **Esconder la consola al arrancar** (`FreeConsole`, `ShowWindow`): la
  ventana parpadea cada vez que arranca el ayudante.
- **`conhost.exe --headless`**: no está documentado, y es una técnica que las
  reglas de detección de los antivirus marcan como sospechosa.

## Consecuencias

- Positivas: ningún cambio en lo que ya funciona; PowerShell conserva los
  códigos de salida; ni artefactos nuevos ni cambios en el build.
- Negativas / compromisos: un ejecutable más en el directorio de estado del
  destino, de unos MB, que el front mantiene al día y que se borra al quitar
  el ayudante; si algún día se firma el agente con Authenticode, la copia no
  tendrá una firma válida, porque cambia un byte de la cabecera.
