---
Nombre: 'Abrir programas en el escritorio del usuario desde el destino'
Número: 19
Estado: 'Aceptada'
Resumen: 'Aceptada por el usuario el 2026-10-07. Desde una shell SSH de un destino Windows (sesión 0, sin escritorio) no se puede abrir nada que necesite ventana: el emulador de Android, la app de escritorio, un navegador. Se propone que el ayudante de escritorio del mouse pad (ADR-0016), que ya corre en la sesión interactiva del usuario, gane una orden para lanzar un programa allí: titan-agent --desktop-run <programa> [argumentos], con su propia marca de conexión y solo por el apretón de manos con HMAC (ADR-0018). Las shells siguen en la sesión 0. Seguridad: no cruza ninguna frontera que el mismo usuario no cruce ya (quien tiene el token del ayudante ya puede teclear en su escritorio, y cualquier shell SSH del usuario puede crear la misma tarea programada interactiva); a cambio se fija que nunca eleva, nunca pasa por la shell ni por asociaciones de ficheros, no ofrece ventanas ocultas, solo lanza en la sesión del propio usuario y deja registro de cada lanzamiento, visible en el panel del agente.'
Decisión: 'Añadir al ayudante de escritorio de Windows una orden para lanzar un programa en la sesión interactiva del usuario, con CreateProcess y el token sin elevar del ayudante, alcanzable solo con el apretón de manos actual y con registro de cada lanzamiento.'
Consecuencias: 'Las shells del destino (y los agentes de IA que corran en ellas) pueden abrir programas gráficos en el PC del usuario sin cambiar dónde vive el daemon. No añade privilegios, pero convierte en una orden de una línea algo que antes exigía crear una tarea programada a mano, y funciona también con la sesión bloqueada; por eso el registro y el panel. Fuera de Windows no aplica de momento.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-06T00:42:00+02:00
Última modificación: 2026-10-07T11:16:21+02:00
---

# ADR-0019 · Abrir programas en el escritorio del usuario desde el destino

> **Nota (2026-10-07), implementación**: el front resuelve el programa con el
> `PATH` de la shell que llama y, si no se da `--cwd`, usa su directorio
> actual. El ayudante solo acepta rutas absolutas. Además de `.bat` y `.cmd`,
> rechaza los nombres que esconden la extensión (puntos o espacios al final)
> y los flujos de datos alternativos. El registro guarda como mucho 32
> argumentos de 512 bytes por petición, y `--remove-desktop` lo borra con el
> resto del ayudante. Si la tarea del ayudante corre en un job que lo permite,
> el programa sale de él, y quitar el ayudante no lo cierra. Detalle en
> [[Abrir programas en el escritorio del usuario desde el destino]].

## Contexto

Win32-OpenSSH lanza los procesos de una conexión SSH en la **sesión 0**, sin
escritorio, y el daemon del agente nace ahí
([[ADR-0009 Agente de nivel 3 portable a todos los destinos]],
[[Experimento supervivencia de procesos en Win32-OpenSSH]]). Para el
terminal da igual, pero nada de lo que se lance desde esa shell puede abrir
una ventana que el usuario vea: el emulador de Android, la app de escritorio,
un navegador. Es justo lo que hace falta cuando se trabaja en el PC desde el
móvil, por ejemplo con un agente de IA que corre en una sesión de titan-ssh y
necesita abrir el emulador para probar una build.

Mover todo el daemon a la sesión del usuario se descarta (ver Alternativas).
En cambio, el **ayudante de escritorio** del mouse pad
([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]],
[[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]) ya
corre en la sesión interactiva, sin elevar, alcanzable por loopback con un
token y con el apretón de manos de
[[ADR-0018 Autenticación mutua en el punto de encuentro del agente]].

## Decisión

### 1. Orden

- `titan-agent --desktop-run [--cwd <ruta>] <programa> [argumentos…]`, en la
  shell del destino. Busca o arranca el ayudante igual que `--input` (misma
  tarea programada, mismo `E_NO_DESKTOP` si no hay sesión iniciada) y le pide
  que lance el programa. Imprime el PID y termina; no espera al programa ni
  recoge su salida.
- El ayudante lo lanza con `CreateProcess`, con **su propio token**, en su
  sesión y su escritorio (`winsta0\default`), con su entorno. El programa no
  depende del ayudante: pararlo no lo cierra.
- Para que la orden se encuentre desde la shell, el daemon pone en el entorno
  de cada sesión del terminal la ruta de su propio ejecutable
  (`TITAN_AGENT`). Se concreta en la implementación.

### 2. Canal

- Una conexión con **su propia marca** (`TTNADRN2`), solo en el apretón de
  manos con HMAC. No hay variante en el apretón antiguo que envía el token, y
  la orden no entra por el canal de control (`TTNADCT*`), que sí lo admite.
- Petición de una línea JSON con tamaño máximo y plazo, como el canal de
  control: argumentos como lista (no una línea de órdenes), directorio de
  trabajo opcional. La respuesta lleva el PID o el error.

### 3. Garantías de seguridad

- **Nunca eleva.** Solo `CreateProcess` con el token del ayudante, que se
  registra con `LeastPrivilege`. Nada de `ShellExecute`, verbo `runas` ni
  `CreateProcessWithLogon`. Si el programa exige administrador
  (`ERROR_ELEVATION_REQUIRED`), se devuelve el error y no se reintenta por
  otra vía: no puede aparecer un aviso de UAC en el escritorio del usuario.
- **Sin intérprete intermedio.** El ayudante compone la línea de órdenes de
  Windows a partir de la lista de argumentos con el entrecomillado estándar
  y la pasa a `CreateProcess` con la ruta del programa resuelta. No pasa por
  `cmd.exe` ni por las asociaciones de ficheros. Quien quiera una shell la
  nombra como programa.
- **Solo la sesión del propio usuario.** El ayudante es una tarea del usuario
  en modo solo interactivo: si quien tiene el escritorio es otra cuenta, el
  ayudante no arranca y la orden falla con `E_NO_DESKTOP`. Nunca se lanza en
  la sesión de otro usuario.
- **Sin ventanas ocultas a propósito.** La orden no tiene opción para ocultar
  la ventana. Un programa puede ocultarse por sí mismo, así que esto es una
  convención y no una barrera.
- **Registro.** Cada lanzamiento se anota en `desktop-run.log`, en el
  directorio de estado (privado como el resto): hora, programa, argumentos,
  directorio, PID o error. Se recorta para no crecer sin límite. El panel del
  agente enseña los últimos.
- **Validación.** Programa no vacío, sin NUL, y directorio absoluto y
  existente si se da.

### 4. Por qué no abre un agujero

Las fronteras de seguridad aquí son otro usuario, otro nivel de integridad y
la red. Ninguna cambia:

| Quién | Hoy | Con esta orden |
|---|---|---|
| Otro usuario del PC, sin administrador | Ve el puerto, pero sin el token no pasa el apretón de manos; no puede leer el token (DACL del directorio de estado). | Igual. |
| Un proceso del usuario con integridad baja o en AppContainer (sandbox de un navegador) | No lee el token: etiqueta *no-read-up* del directorio de estado. | Igual. |
| Un atacante en la red | El ayudante solo escucha en `127.0.0.1`. | Igual. |
| El propio usuario (shell SSH o proceso de integridad media) | Ya puede ejecutar lo que quiera en su escritorio: crear la misma tarea programada interactiva (es como arranca el ayudante) o, con el token, teclear en él con el mouse pad. | Lo hace con una orden, también con la sesión bloqueada. |
| Un administrador | Puede todo. | Igual. |

Lo único nuevo es la comodidad, y que el lanzamiento no se ve teclear. Por eso
el registro y el panel. El token del escritorio ya equivalía a ejecutar
programas como el usuario.

### 5. Fuera de esta decisión

- **Linux.** En X11 y Wayland una shell SSH puede abrir ventanas si conoce
  `DISPLAY` o `WAYLAND_DISPLAY`. Se decidirá si hace falta cuando lleguen sus
  ayudantes.
- **Capturar la pantalla del escritorio** desde la shell. Es otra orden, con
  su propio análisis de privacidad.
- **Que el terminal entero viva en el escritorio.** Ver Alternativas.

## Alternativas consideradas

- **Daemon en la sesión del usuario** (lanzado por la tarea programada):
  solo funciona con la sesión de Windows iniciada, muere al cerrarla (contra
  [[ADR-0014 Sesiones del agente sin caducidad]]) y cambia el token de las
  shells (los administradores entrarían sin elevar). Descartada.
- **Que cada shell cree su propia tarea programada** (`schtasks /create /it` y
  `/run`): funciona hoy sin código, pero deja una tarea por programa, sin
  registro ni panel, y cada herramienta tendría que reinventarlo. Queda como
  atajo de desarrollo, no como funcionalidad.
- **Servicio de Windows con `CreateProcessAsUser`**: exige administrador.
  Descartada, igual que en ADR-0016.
- **Meter la orden en el canal de control del ayudante**: ese canal acepta
  aún el apretón antiguo. Una marca propia, solo con HMAC, deja la orden
  fuera de ese camino aunque se tarde en retirarlo.

## Consecuencias

- Positivas: abre el emulador, la app de escritorio o cualquier programa
  gráfico desde el terminal, también desde el móvil; reutiliza el ayudante,
  su tarea, su token y su apretón de manos; no cambia dónde corre el daemon ni
  el token de las shells.
- Negativas / compromisos: convierte en una orden sencilla algo que antes
  costaba más (sin añadir privilegios); funciona con la sesión bloqueada; el
  ayudante pasa a ser algo más que el mouse pad, y el panel tiene que
  explicarlo; un programa que pide administrador no se puede abrir así.
