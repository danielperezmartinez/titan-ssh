---
Nombre: 'Reabrir la app tras Instalar y reiniciar en Linux'
Estado: 'En curso'
Resumen: 'En Linux, «Instalar y reiniciar» instala la versión nueva pero no reabre la app. El instalador hereda de la JVM las variables que el lanzador de jpackage pone en su propio proceso (_JPACKAGE_LAUNCHER y LD_LIBRARY_PATH con lib/app). El lanzador relanzado las toma por un reinicio interno de la JVM, no lee su configuración y se cierra al instante. Visto al pasar un tar.gz de la beta.20 a la 1.0.0. Arreglo para 1.0.1: quitar esas variables del entorno del instalador y conservar sus registros.'
Decisiones: 'El 2026-10-16 la sesión de control lo asigna a una 1.0.1. No hace falta cambiar ninguna ADR: el comportamiento es el de [[ADR-0025 Actualización automática de la app]] §2 («tras actualizar, la app se reabre»), que en Linux no se cumplía.'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-16T20:20:00+02:00
Última modificación: 2026-10-16T20:20:00+02:00
---

# Reabrir la app tras Instalar y reiniciar en Linux

## Objetivo

Que «Instalar y reiniciar» reabra la app en Linux, como ya hace en Windows,
según [[ADR-0025 Actualización automática de la app]] §2. Sale de la
comprobación de la 1.0.0 en [[Actualización automática de la app]].

## Qué pasó

En un portátil con Arch y el `tar.gz` instalado en una carpeta del usuario, se
pulsó «Instalar y reiniciar» en la beta.20 para pasar a la 1.0.0:

- `update.log`: `installing 1.0.0 (automatic=false, relaunch=true)` y
  `install of 1.0.0: Started`, a las 17:54:23Z.
- La carpeta de la app se sustituyó en ese mismo minuto, así que la JVM vieja
  salió a tiempo y la instalación terminó bien (la 1.0.0 funciona).
- La app no se reabrió. La siguiente escritura en la caché de actualizaciones
  es de dos minutos después: la 1.0.0 que abrió el usuario a mano.
- No quedaron `helper.log` ni `result`: la 1.0.0 borra la carpeta de la versión
  instalada al arrancar (`UpdateEngine`, limpieza de descargas). No hay volcados
  de memoria y el journal de usuario no dice nada.

## Causa

El lanzador de Linux de jpackage (`LinuxLauncherLib.cpp` y `AppLauncher.cpp`
del JDK) hace dos cosas **en su propio proceso** (`setenv`, por eso no salen en
`/proc/<pid>/environ`) antes de crear la JVM:

1. Añade `<raíz>/lib/app` a `LD_LIBRARY_PATH`.
2. Pone `_JPACKAGE_LAUNCHER=<hash del LD_LIBRARY_PATH original>`. Le sirve para
   detectar cuando la propia JVM lo vuelve a ejecutar con `execve`.

`DesktopUpdatePlatform.install` lanza `install-linux.sh` con `ProcessBuilder`
sin tocar el entorno, así que el script hereda las dos variables. Al final, el
script ejecuta `setsid "$launcher"`. El lanzador nuevo:

- encuentra `_JPACKAGE_LAUNCHER`;
- compara su hash con el de su `LD_LIBRARY_PATH`, que ya lleva `lib/app`, y no
  coincide;
- deduce que es un reinicio interno de la JVM (`setInitJvmFromCmdlineOnly`), no
  lee el `.cfg` (clase principal, classpath, opciones) y la JVM termina al
  instante sin abrir nada.

Encaja con lo observado:

- **Windows** no tiene ese mecanismo y la reapertura funciona.
- **Las pruebas en contenedores** de ADR-0025 ejecutaban el script a mano, no
  desde una JVM lanzada por jpackage, así que no llevaban esas variables.
- **Afecta igual a `.deb` y `.rpm`** cuando se pulsa «Instalar y reiniciar».
- **No afecta a la instalación automática al cerrar**, que no reabre la app
  (`relaunch=false`).

Reproducido el 2026-10-16 en un contenedor Ubuntu 24.04 con Xvfb y el
`tar.gz` publicado de la 1.0.0. Con
`_JPACKAGE_LAUNCHER=0 LD_LIBRARY_PATH=:<raíz>/lib/app <raíz>/bin/titan-ssh`,
el lanzador imprime `Usage: java [options] <mainclass> [args...]` y termina;
sin esas variables, la app sigue abierta.

## Criterios de finalización

- **Entorno limpio para el instalador**: `DesktopUpdatePlatform.install` quita
  `_JPACKAGE_LAUNCHER` del entorno del `ProcessBuilder` y retira de
  `LD_LIBRARY_PATH` la entrada `<raíz>/lib/app` que añadió el lanzador. Si la
  variable queda vacía, la elimina.
- **Defensa en el script**: `install-linux.sh` relanza con
  `env -u _JPACKAGE_LAUNCHER`, por si lo arranca otra versión de la app.
- **Registros que sobreviven**:
  - el script escribe en `update.log` (o en un fichero junto a él, fuera de la
    carpeta de la versión) su código de salida y el comando de relanzamiento;
  - la salida de los primeros segundos del lanzador relanzado va a un
    `relaunch.log` en lugar de a `/dev/null`;
  - la limpieza al arrancar no borra esos ficheros.
- **Prueba**: un test de `DesktopUpdatePlatform` que compruebe el entorno del
  proceso que lanza. Y una prueba real en Linux, desde la app empaquetada (no
  ejecutando el script a mano): de un `tar.gz` a otro con «Instalar y
  reiniciar», y lo mismo con un `.deb`.
- Revisar si el instalador de Windows hereda alguna variable del lanzador que
  pueda dar un problema parecido. Allí la reapertura sí funciona.
- Sale en una **1.0.1**.

## Verificación

Hecho el 2026-10-16 en la rama `fix-linux-relaunch`:

- **Código**:
  - `DesktopUpdatePlatform.withoutLauncherMarks` quita `_JPACKAGE_LAUNCHER`
    del entorno del instalador y retira `<raíz>/lib/app` de `LD_LIBRARY_PATH`
    (solo en Linux).
  - `install-linux.sh` hace `unset _JPACKAGE_LAUNCHER` y anota en `helper.log`
    el inicio, el código de salida y el relanzamiento. Los errores del lanzador
    relanzado van a `relaunch.log`.
  - `helper.log` pasa de la carpeta de la versión a la de `update.log`, y la
    limpieza al arrancar conserva los `*.log`.
- **Tests**: los 22 de `*.update.*` pasan en `:shared:desktopTest`. Son nuevos
  los tres de `DesktopUpdatePlatformTest` (entorno) y
  `the_installer_logs_outlive_the_installed_version`.
- **Script en contenedor** (Ubuntu 24.04, Xvfb, `tar.gz` real de la 1.0.0):
  ejecutado con las variables que hereda de la JVM y `relaunch=1`, el script
  de la 1.0.0 instala (`result=0`), pero la app no se abre. El script nuevo
  instala y la app sigue abierta a los 15 s.
- **Pendiente: prueba real desde la app empaquetada.** Solo se puede ver al
  actualizar **desde** la versión que lleve el arreglo, porque el instalador
  que se ejecuta es el de la versión en marcha. Así, el paso de la 1.0.0 a la
  1.0.1 tampoco reabrirá la app, y la reapertura se comprobará en la
  actualización siguiente a la 1.0.1. Para no esperar, se puede probar antes
  con dos builds locales y un origen de pruebas (`-PtitanUpdateApi`), como en
  [[Actualización automática de la app]].

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
