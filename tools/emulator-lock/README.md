# Turno del emulador de Android

Varias sesiones (agentes o personas, cada una en su worktree) pueden probar en
el mismo emulador. Dos cosas evitan que se pisen:

1. **Cada worktree instala su propia app.** Una build de debug hecha desde un
   worktree enlazado de git lleva el nombre de la carpeta del worktree como
   sufijo del paquete y de la etiqueta del lanzador (p. ej.
   `io.github.danielperezmartinez.titanssh.mi_worktree`, "titan-ssh mi_worktree").
   El árbol principal conserva el paquete normal. Así varias builds conviven
   instaladas y ninguna sesión borra la de otra.
   - `-PtitanDevSuffix=<nombre>` fija el sufijo a mano; `-PtitanDevSuffix=`
     lo quita.
   - Las builds de release nunca llevan sufijo.
   - Cada paquete tiene sus propios datos: la primera vez hay que crear el
     destino de pruebas (ver `tools/test-sshd/`) o copiar la configuración
     con `run-as`.
2. **Un turno para manejar la pantalla.** Instalar, tocar, escribir o hacer
   capturas con `adb` solo se hace con el turno tomado.

## Uso

```sh
tools/emulator-lock/emulator-lock.sh acquire --note "qué voy a probar"
# ... instalar, manejar la app, capturas ...
tools/emulator-lock/emulator-lock.sh release
```

- `acquire` sale con `0` si toma (o renueva) el turno y con `1` si está
  ocupado, mostrando quién lo tiene.
- `acquire --wait [--timeout SEG]` espera hasta conseguirlo y sale ya con el
  turno tomado (`3` si se agota el tiempo).
- `status` dice si está libre, ocupado o abandonado.
- `release` solo suelta el turno propio; `release --force` suelta el de otro
  (solo si se sabe que está abandonado).

Volver a llamar a `acquire` con el turno tomado lo renueva. Un turno que no se
renueva en 20 minutos se da por abandonado y el siguiente `acquire` se lo queda,
avisando de quién era. Si una prueba larga va a pasar de ahí, se renueva antes.

El turno es solo para manejar la pantalla. Al terminar se suelta, aunque la app
quede instalada y el emulador abierto para que la persona la pruebe: su build
no la toca nadie, porque cada worktree tiene su paquete.

## Detalles

- El candado es una carpeta creada con `mkdir` (atómico, también en Windows)
  fuera de cualquier worktree, en `%LOCALAPPDATA%\titan-ssh-dev\locks\` en
  Windows o `~/.cache/titan-ssh-dev/locks/` en Linux. No se versiona.
- Hay un candado por emulador, según `ANDROID_SERIAL`. Con varios emuladores
  abiertos, cada sesión fija `ANDROID_SERIAL` y toma el turno de ese.
- El titular es la raíz del worktree (`git rev-parse --show-toplevel`). Dos
  sesiones en el mismo árbol deben distinguirse con
  `TITAN_EMULATOR_LOCK_OWNER=<nombre>`.
- Variables: `TITAN_EMULATOR_LOCK_TTL` (segundos hasta darlo por abandonado,
  1200), `TITAN_EMULATOR_LOCK_POLL` (segundos entre intentos con `--wait`, 5),
  `TITAN_EMULATOR_LOCK_DIR` (carpeta de los candados).
- Necesita bash (en Windows, Git Bash).
