---
Nombre: Terminal multipestaña con sesiones simultáneas
Estado: En curso
Resumen: 'Terminal multipestaña v1 entregado y verificado headless: emulador ANSI, gestor de pestañas (abrir/alternar/cerrar/reordenar) cableado al motor SSH desde la lanzadera, estados por pestaña, render + entrada de teclado con resize real, barra accesoria Android, split de escritorio y pestaña a pantalla completa. Queda EN CURSO: falta verificación real (conexión viva por la UI en escritorio/dispositivo, entrada por teclado software Android) y el driver del estado "reconectando" (pertenece a Resiliencia nivel 1).'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]]; consume [[Motor de conexión SSH]], [[ADR-0003 Modelo de resiliencia por niveles]] y [[ADR-0005 Autenticación SSH y verificación de host]] (TOFU).
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-18T15:40:00+02:00
---

# Terminal multipestaña con sesiones simultáneas

## Objetivo

Ofrecer el área de Sesiones: una pantalla de terminal que soporte varias
conexiones SSH simultáneas mediante pestañas, lanzadas a partir de las
configuraciones guardadas, con una experiencia ágil para alternar, cerrar y mover
pestañas. Al estar en una pestaña se ve el terminal listo para trabajar.

## Criterios de finalización

- Se pueden tener varias sesiones abiertas a la vez, cada una en su pestaña.
- Alternar, cerrar y **mover/reordenar** pestañas es ágil y cómodo.
- Cada pestaña muestra su **estado**: conectando / conectado / reconectando (loader
  del nivel 1, ver [[Resiliencia de sesión ante microcortes de red]]) / caída.
- **Android:** barra de **teclas accesorias** sobre el teclado del sistema (Esc,
  Tab, Ctrl, Alt, flechas y símbolos como `|` `/` `-` `~`), ya que el teclado
  software no las trae; gestión de pegar y scroll.
- En **escritorio**, posibilidad de dividir la vista en dos terminales; en
  **Android**, una pestaña a pantalla completa.

## Contexto ya disponible (2026-09-18)

- El área de Sesiones existe como **lanzadera** (`SessionsArea`): lista las
  sesiones guardadas y muestra la conexión que cada una resuelve (config → motor),
  con un botón `[>] Lanzar` aún sin cablear. Entregada con
  [[Panel de gestión de hosts y sesiones]].
- El **nivel de resiliencia por sesión** ya se configura en el panel
  (`ResilienceLevel`, [[ADR-0003 Modelo de resiliencia por niveles]]).
- Esta tarea es la que debe **cablear el lanzamiento**: abrir la sesión sobre el
  [[Motor de conexión SSH]] en una pestaña y, en ese flujo, **ejecutar los scripts
  de inicio** ([[Scripts de inicio por sesión]], que queda bloqueada por esta para
  su parte de ejecución).

## Resultado (v1, 2026-09-18)

Terminal multipestaña entregado en el paquete `im.gar.titanssh.terminal`
(lógica) + `im.gar.titanssh.ui` (Compose), cableado sobre el
[[Motor de conexión SSH]] ya existente.

### Núcleo (lógica pura, con tests headless)

- **`TerminalEmulator`** — emulador VT100/ANSI pragmático: rejilla de celdas con
  cursor, scrollback acotado y parser de secuencias de escape de uso diario
  (texto UTF-8 con salto de línea, C0 `BEL/BS/HT/LF/VT/FF/CR`, CSI de cursor
  `CUU/CUD/CUF/CUB/CUP/CHA/VPA`, borrado `ED/EL`, `SGR` con negrita, inverso,
  los 16 colores ANSI, indexado `38/48;5` y verdadero color `38/48;2`,
  guardar/restaurar cursor e índice inverso). Consume-e-ignora OSC (título) y
  modos privados DEC para que no ensucien la pantalla. Produce
  `TerminalSnapshot` inmutable. **Fuera de v1** (degradan sin romper): pantalla
  alterna (`1049`), regiones de scroll (`DECSTBM`), tab-stops programables y
  selección de charset.
- **`AnsiPalette`** — paleta del terminal: índices 0..15 según
  [[Tokens visuales dark-first base opencode]], más cubo 6x6x6 (16..231) y rampa
  de grises (232..255) estándar de xterm.
- **`TabList`** — modelo inmutable de orden y pestaña activa (abrir, activar,
  cerrar con elección de vecino, mover/reordenar); solo ids, sin sesión viva.
- **`TerminalKeys`** — traducción pura de teclas a bytes del PTY (especiales,
  `Ctrl-x`, `Alt-x`, texto) y el layout de la barra accesoria Android.
- **`CredentialResolver`** — materializa `SshCredentials` a partir del
  `HostAuth` (solo referencias) leyendo el `SecretStore` en tiempo de conexión
  (ADR-0001); la clave hardware no se materializa (la resuelve el motor).

### Runtime

- **`SessionTab`** — una pestaña: gobierna una conexión y su shell, alimenta el
  emulador desde la salida del `SshShell`, expone `status` y `snapshot`
  observables y `sendBytes`/`resize`. Los fallos aterrizan como
  `TabPhase.FAILED` (la pestaña no desaparece). El TOFU de primera vez
  ([[ADR-0005 Autenticación SSH y verificación de host]]) aflora como
  `pendingHostKey` para que la UI confirme en línea.
- **`SessionManager`** — dueño de las pestañas abiertas; implementa el
  **lanzamiento** (config → motor → pestaña) que la lanzadera dejó pendiente en
  [[Panel de gestión de hosts y sesiones]].
- **Estados por pestaña**: conectando / conectado / (reconectando) / caída /
  fallo, con marcador ASCII y color semántico según
  [[Vocabulario ASCII ampliado y disciplina de color]].
- **Punto de enganche de scripts de inicio**: `SessionTab.onShellReady(shell,
  resolved)` se invoca al abrir el shell (hoy no-op). La **ejecución** de los
  scripts es de [[Scripts de inicio por sesión]], que queda desbloqueada por
  este enganche.

### UI (Compose dark-first)

- **`TerminalView`** — pinta el snapshot (colores ANSI, cursor), mide la celda
  mono para calcular columnas/filas y hace `resize` real del PTY; entrada por
  eventos de teclado (`onPreviewKeyEvent`: teclados físicos y escritorio). En
  Android añade barra accesoria (Esc, Tab, Ctrl/Alt pegajosos, flechas,
  `| / - ~`), pegar (portapapeles) y un campo oculto que levanta el teclado
  software.
- **`SessionsArea`** (reescrita) — lanzadera cuando no hay pestañas; con
  pestañas, tira de pestañas (alternar, cerrar `[x]`, reordenar `[<]`/`[>]`,
  nueva `[+]`), **split de dos terminales en escritorio** y **pestaña a pantalla
  completa en Android**.

### Superficies expect/actual añadidas

`createKnownHostsStore()` (fichero `known_hosts` en dir de config/`filesDir`) e
`isAndroidRuntime()`, con actuals en `androidMain`/`desktopMain`.

## Verificación

**Headless (hecho):**

- Build de los cuatro targets → `BUILD SUCCESSFUL`:
  `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin`. APK:
  `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- `:shared:desktopTest` sin fallos. Tests nuevos (31): `TerminalEmulatorTest`
  (13: texto/CR-LF/BS/wrap/scroll+scrollback/CUP/SGR reset+truecolor+bright/EL/
  OSC ignorado/UTF-8 multibyte y partido entre feeds/resize), `TabListTest` (9:
  abrir/duplicado/activar/cerrar con vecino/borde vacío/reordenar), `Terminal
  KeysTest` (4: especiales, `Ctrl`, `Alt`, cobertura de la barra accesoria),
  `CredentialResolverTest` (5: password/clave+passphrase/sin passphrase/hardware
  sin tocar el store/secreto ausente). Los tests previos siguen pasando.
- Warning conocido y benigno «Default Kotlin Hierarchy Template» (por el source
  set `jvmShared`) y deprecación de `LocalClipboardManager` (no rompe; migrar a
  `LocalClipboard` es trabajo futuro).

**Comprobado en dispositivo (2026-09-18, usuario)** en el pixel-9-pro-xl: crear
hosts y sesiones y su persistencia; abrir **dos sesiones simultáneas**, tenerlas a
la vez, **reordenar** y **cerrar** pestañas — todo correcto. Tras entregar la
[[Gestión de claves y secretos UI]], **la conexión viva funciona** (generó clave
hardware, enroló y conectó). 

Ajustes detectados en esa prueba y su tratamiento:

- **Teclado software no aparecía** al conectar (solo se veía la barra accesoria).
  Causa: al tocar el terminal se enfocaba un panel `focusable` no editable, que no
  levanta el IME. **Arreglado** en `TerminalView.kt`: en Android el toque enfoca el
  campo oculto de captura y llama a `LocalSoftwareKeyboardController.show()`.
  Pendiente de re-comprobar en dispositivo.
- **Reordenar pestañas** pasa a drag-and-drop con preview en vivo →
  [[Reordenar pestañas de sesión con drag and drop]].

**Pendiente de comprobación real (por eso queda `En curso`):**

- **Conexión viva a través de la UI**: no verificada por mí (no puedo arrancar la
  ventana de escritorio ni un dispositivo, ni conectar a un host real con
  credenciales). Se añadió `SessionTabIntegrationTest` (opt-in, se salta sin
  credenciales, mismas env que `SshjIntegrationTest`) que abre una `SessionTab`
  real contra el host de pruebas, espera `CONNECTED`, envía `echo` y comprueba
  que la salida aparece en el snapshot: **falta que el usuario lo corra con sus
  credenciales** y confirme visualmente `:desktopApp:run`.
- **Android**: barra accesoria, pegar, pantalla completa y sobre todo la
  **entrada por teclado software** (truco de ancla; el IME puede interferir) son
  compile-verified pero necesitan comprobación en el pixel-9-pro-xl. La ruta
  fiable de entrada es teclado físico + barra accesoria.
- **Estado "reconectando" (loader nivel 1)**: el estado existe y se pinta, pero
  **nada lo dispara todavía**: el motor de reconexión de cliente es
  [[Resiliencia de sesión ante microcortes de red]] (ADR-0003 nivel 1). Al caer
  la conexión hoy la pestaña pasa a "caída". Criterio diferido a esa tarea.

## Nota catálogo técnico

Las superficies reutilizables **ya están catalogadas** (la taxonomía del Catálogo
estaba acordada desde el registro de `SecretStore`; el texto "por definir" era
stale y se ha corregido): [[TerminalEmulator]] (con `AnsiPalette`/snapshot),
[[SessionManager]] (con `SessionTab`), [[TabList]], [[TerminalKeys]],
[[CredentialResolver]] y [[TerminalView]]. Se añadió el valor `Terminal` al
vocabulario de Feature (el README autoriza ampliarlo cuando un caso no encaje).
