---
Nombre: 'Abrir programas en el escritorio del usuario desde el destino'
Estado: 'En curso'
Resumen: 'En Windows, la shell de una sesión de titan-ssh corre en la sesión 0 y no puede abrir nada con ventana (emulador de Android, app de escritorio, navegador). Se añade titan-agent --desktop-run, que pide al ayudante de escritorio del mouse pad que lance el programa en la sesión interactiva del usuario, sin elevar, sin shell intermedia, solo con el apretón de manos con HMAC y con registro visible en el panel del agente. Diseño y análisis de seguridad en ADR-0019, aceptada el 2026-10-07.'
Decisiones: 'El usuario elige el 2026-10-06 esta vía (ampliar el ayudante) frente a mover el daemon a su sesión o a un atajo con tareas programadas, con la condición de que no abra un agujero de seguridad, y acepta el 2026-10-07 el diseño y el análisis de [[ADR-0019 Abrir programas en el escritorio del usuario desde el destino]].'
Bloqueada: []
Fecha de creación: 2026-10-06T00:42:00+02:00
Última modificación: 2026-10-07T11:16:21+02:00
---

# Abrir programas en el escritorio del usuario desde el destino

## Objetivo

Que desde el terminal de una sesión de titan-ssh contra un destino Windows se
pueda abrir un programa gráfico en el escritorio del usuario. Ahora no se
puede, porque la shell corre en la sesión 0. El caso que lo motiva: trabajar
en el PC desde el móvil con un agente de IA dentro de una sesión de titan-ssh
que necesita abrir el emulador de Android o la app de escritorio para probar
una build.

Diseño, garantías y análisis de amenazas en
[[ADR-0019 Abrir programas en el escritorio del usuario desde el destino]].
Se apoya en el ayudante de
[[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]] y en el
apretón de manos de
[[ADR-0018 Autenticación mutua en el punto de encuentro del agente]].

## Situación de partida (2026-10-06)

- Comprobado desde una sesión de titan-ssh en el PC de desarrollo: la shell
  está en la sesión 0 y no es interactiva. adb funciona contra un emulador
  que ya esté abierto en la sesión del usuario (instalar, abrir la app,
  capturas), pero no se puede arrancar el emulador ni abrir la app de
  escritorio.

## Avance (2026-10-07)

- Implementado en el agente: `--desktop-run [--cwd]`, marca `TTNADRN2`
  (`desktoprun.go`, `desktoprun_windows.go`), `TITAN_AGENT` en el entorno de
  las shells (`daemon.go`) y `desktop.runs` en `--status --json`.
  `--remove-desktop` borra también el registro. El registro guarda como
  mucho 32 argumentos de 512 bytes por petición. La comprobación de `.bat` y
  `.cmd` ignora los puntos y espacios finales que Windows quita, y se
  rechaza un programa con flujo de datos alternativo (`x.exe:flujo`).
- App: `AgentDesktopRun` en `AgentStatus.kt`, líneas en
  `AgentInsights.desktopRunLines` y la fila del panel pasa a "Ayudante de
  escritorio", plegable con los últimos programas abiertos.
- Tests: Go en Windows y en Linux con `-race` (validación, extremo a extremo
  con el ayudante, rechazo por el apretón antiguo y por el canal de control,
  recorte del registro, contrato JSON); Kotlin `AgentDesktopRunTest`.
  `:shared:desktopTest` completo: solo falla `DesktopSecretStoreTest`, porque
  se ejecutó desde una sesión SSH con clave, en la que el Administrador de
  credenciales de Windows no está disponible (error 1312). No tiene que ver
  con este cambio.
- Prueba real desde una sesión SSH (sesión 0, shell elevada) en el PC de
  desarrollo, con un directorio de estado aparte: el Bloc de notas se abrió
  en la sesión 1 del usuario y quedó en el registro; un `.cmd` y `regedit`
  (que pide administrador) se rechazaron sin aviso de UAC; tras
  `--remove-desktop` el Bloc de notas siguió abierto y la tarea de prueba
  desapareció. Limpieza hecha.
- Emulador (build de debug) contra el PC de desarrollo, tras abrir un Bloc
  de notas con `--desktop-run` en su directorio de estado real: el panel
  muestra "Ayudante de escritorio · … · 1 programa abierto en el escritorio"
  y, desplegado, "notepad.exe · PID …". El ayudante del PC pasó a la versión
  de desarrollo; el siguiente uso de una versión publicada lo sustituye.
- Pendiente: que el usuario lo pruebe con la versión publicada (abrir el
  emulador y la app de escritorio con `--desktop-run`). Actualizar el agente
  del destino cierra sus sesiones.

## Plan

1. ~~Aceptar o ajustar la ADR-0019 con el usuario.~~ Aceptada el 2026-10-07.
2. Agente: marca `TTNADRN2`, petición y respuesta, lanzamiento con
   `CreateProcess` y el token del ayudante, registro `desktop-run.log` y
   modo `--desktop-run`. Tests de la composición de la línea de órdenes, de
   la validación y de que la marca no se acepta por el apretón antiguo ni
   por el canal de control.
3. Daemon: `TITAN_AGENT` en el entorno de cada sesión del terminal.
4. `--status --json` y panel del agente: últimos lanzamientos.
5. Documentación: `agent/README.md` y catálogo técnico si aplica.

## Criterios de finalización

- `titan-agent --desktop-run` abre un programa en el escritorio del usuario
  desde una sesión de titan-ssh, y lo comprueba el usuario: el emulador de
  Android y la app de escritorio.
- Sin sesión de escritorio iniciada, falla con `E_NO_DESKTOP` y no lanza nada.
- Un programa que exige administrador falla con un error claro y no muestra
  ningún aviso de UAC.
- La orden no se acepta por el apretón de manos antiguo ni por el canal de
  control (tests).
- Cada lanzamiento aparece en el registro y en el panel.
- Revisión de seguridad del diff según la nota-índice de auditorías antes de
  publicar.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
