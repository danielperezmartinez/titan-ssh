---
Nombre: 'Seguimiento de tareas pendientes'
Estado: 'En curso'
Resumen: 'Hoja de ruta viva con todas las tareas abiertas del proyecto, ordenadas por prioridad, para irlas completando una a una en sesiones sucesivas nombrando esta nota. Indica qué tareas conviene hacer juntas en la misma sesión y qué pasos necesitan una acción del usuario. Orden: primero lo que hay que hacer con el árbol de trabajo limpio y antes de publicar (cambio de identificador y licencia); después la base de release hasta la primera pre-release con Obtainium, que sustituye a pasar la APK a mano; los canales propios del usuario (AUR, winget); el hueco funcional de scripts sobre el agente; el nivel 3 portable; los scripts reutilizables y los túneles; y al final los canales públicos (Flathub, IzzyOnDroid), la firma de SignPath y el aviso de versión. Google Play queda aparcado.'
Decisiones: 'Prioridad acordada el 2026-09-24 a partir de [[ADR-0011 Distribución y canales de publicación]] y [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. El nivel 3 portable conserva su propio orden interno en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-24T00:05:00+02:00
Última modificación: 2026-10-01T19:45:00+02:00
---

# Seguimiento de tareas pendientes

## Cómo usar esta nota

En cada sesión, cuando el usuario nombre esta tarea:

1. Leer esta nota y elegir el **primer paso sin marcar** cuyas dependencias
   estén hechas (o el paso que indique el usuario).
2. Abrir la nota de cada tarea del paso y seguir el protocolo de la bóveda
   ([[README]]): pasar la tarea a `En curso`, trabajar, verificar y dejarla en
   `Hecha`.
3. Al terminar, marcar aquí la casilla, añadir una línea en el **Registro** y
   actualizar `Última modificación`.
4. Si aparece una tarea nueva, o una cambia de alcance o de bloqueo, colocarla
   en el orden que corresponda.

**Fuente de verdad**: la propiedad `Estado` de cada tarea. Las casillas de esta
nota son solo el progreso de la hoja de ruta; si no coinciden, manda la tarea.

Leyenda: **👤** = el paso necesita una acción o decisión del usuario.
**Juntas** = tareas que conviene hacer en la misma sesión (y, si procede, en el
mismo commit).

## Orden de prioridad

### Fase 1 · Antes de publicar nada

- [x] **1. Identificador y licencia** (juntas)
  - [[Cambiar el identificador de la app a io.github]]
  - [[Licencia GPL-3.0-or-later del proyecto]] (el fichero `LICENSE`, los
    avisos y el README; la pantalla Acerca de puede esperar al paso 2)
  - *Por qué primero*: el renombrado de paquetes toca unos 100 ficheros Kotlin
    y choca con cualquier trabajo en curso. Hay que hacerlo con el árbol limpio,
    antes de que el resto de tareas añada código bajo `im/gar/titanssh`. El
    `applicationId` queda fijado para siempre en la primera versión pública.
    La licencia es corta y comparte el mismo commit de "preparar el repositorio".
- [x] **2. Versionado único** + pantalla Acerca de
  - [[Versionado único desde tag de git]]
  - La parte de UI de [[Licencia GPL-3.0-or-later del proyecto]] (Acerca de, con
    versión y avisos legales), que necesita la versión.
- [x] **3. Icono** 👤 (el usuario decide el diseño; se puede hacer en paralelo a
  1–2)
  - [[Icono y recursos gráficos de la app]]

### Fase 2 · Primera pre-release (deja de pasarse la APK a mano)

- [x] **4. Release de escritorio**
  - [[Configuración de release del escritorio]]
  - Depende de 1–3.
- [x] **5. Release de Android y agente en la APK** (juntas) 👤 (el usuario crea
  y custodia el keystore)
  - [[Firma y configuración de release Android]]
  - [[Verificar empaquetado del agente en APK Android]]
  - *Juntas*: las dos se verifican con la misma APK en el Pixel. Conviene
    comprobar que los binarios del agente sobreviven a R8 y a la build de
    release, no solo a la de debug.
  - Depende de 1–3. Se puede hacer en paralelo a 4.
  - Tarea pequeña que va junto a este paso:
    [[Respetar las barras del sistema en Android]] (el título se solapa con la
    barra de estado). Se verifica en el mismo dispositivo y conviene que la
    primera pre-release ya no lo tenga.
- [x] **6. Pipeline y Obtainium** (juntas) — **hito: primera pre-release**
  - [[Pipeline de release en GitHub Actions]]
  - [[Canal Android Obtainium desde GitHub Releases]]
  - *Juntas*: la prueba del pipeline es publicar `v0.1.0-beta.1` y recibirla en
    el Pixel con Obtainium, y después una segunda versión como actualización.
  - Depende de 4 y 5.

### Fase 3 · Canales del propio usuario

- [ ] **7. winget** 👤 (el usuario firma el CLA y crea un token de GitHub para
  el fork de `winget-pkgs`)
  - [[Canal Windows winget]]
  - Primer PR abierto:
    [microsoft/winget-pkgs#441824](https://github.com/microsoft/winget-pkgs/pull/441824).
  - Las pre-releases van a su propio canal: `DanielPerezMartinez.TitanSSH.Beta`.
  - Iba junto a AUR, que se separó el 2026-09-26 al cerrar AUR el registro de
    cuentas (ver **A la espera**).
  - **A la espera de terceros**: solo falta que Microsoft fusione la PR. No
    impide seguir: el siguiente paso es el 8. El seguimiento diario y lo
    que queda por hacer están en [[Revisión diaria de winget y AUR]].

### A la espera de terceros

- [ ] **AUR** 👤 [[Canal Arch Linux AUR]] (`Pendiente`): todo lo técnico está
  hecho y probado. Se retoma cuando AUR reabra el registro de cuentas nuevas
  (ver las noticias de Arch), siguiendo los pasos de la sección **Pendiente**
  de la tarea. No bloquea nada: el job `aur` no se ejecuta sin la variable
  `AUR_ENABLED`. Se revisa cada día con
  [[Revisión diaria de winget y AUR]]. Hasta tener Flatpak (paso 11), en Bazzite y Arch se usa el
  `tar.gz` del Release.

### Fase 4 · Producto

- [x] **8. Scripts de inicio en sesiones del agente**
  - [[Scripts de inicio por sesión sobre el agente]] (`Hecha` el 2026-09-26)
  - *Por qué aquí*: es un hueco en un pilar del producto (la automatización
    gratuita): hasta este paso, una sesión de nivel 3 no ejecutaba el `cd`
    inicial ni los scripts (`SessionTab` retornaba por la ruta de
    `AgentTransport` antes de `StartScriptAutomation`). Es independiente y
    pequeño comparado con el nivel 3 portable.
  - La tarea pequeña que iba junto a este paso,
    [[Indicar cuando la conexión deja de responder]], queda **aplazada** por
    decisión del usuario (ver **Aparcada**).
- [x] **9. Nivel 3 portable a todos los destinos** (seguir el orden interno de
  [[Nivel 3 portable a todos los destinos]])
  - [x] 9.1 [[titan-agent PTY propio multiplataforma]] (`Hecha` el 2026-09-27)
  - [x] 9.2 [[titan-agent instancia única y directorio de estado]] (`Hecha` el
    2026-09-27)
  - [x] 9.3 [[titan-agent punto de encuentro TCP loopback con token]] (`Hecha`
    el 2026-09-27, en la misma sesión que 9.2)
  - [x] 9.4 [[titan-agent daemon en Windows]] (`Hecha` el 2026-09-27; la
    cuenta estándar de pruebas se conserva para futuras pruebas)
  - [x] 9.5 [[Instalación del agente en destinos Windows y multi-SO]] (`Hecha` el
    2026-09-27, al publicarse `v0.1.0-beta.3` con los 13 binarios)
  - [x] 9.6 [[Diagnóstico cuando el nivel 3 no está disponible]] (`Hecha` el
    2026-09-27)
  - Cada subtarea que cambie el agente sale en la siguiente pre-release, y así se
    prueba desde Obtainium o la app instalada.
- [x] **10. Scripts reutilizables**
  - [[Scripts y túneles reutilizables de primera clase]] (`Hecha` el 2026-09-27)
  - Decidido el 2026-09-27 en
    [[ADR-0013 Biblioteca de scripts unificada con los snippets]]: los snippets
    se unifican en una biblioteca de scripts, y los túneles salen de este paso.
  - *Por qué después del nivel 3*: es una mejora de organización de algo que ya
    funciona (scripts embebidos en cada sesión), mientras que el nivel 3
    portable completa la resiliencia, el pilar principal.
  - Tarea pequeña que salió al cerrarlo:
    [[Scroll lateral en las pestañas de Configuración]] (`Hecha` el
    2026-09-27).
- [x] **10b. Túneles que funcionan**
  - [[Ejecutar los túneles de las sesiones]] (`Hecha` el 2026-09-28)
  - Sale del paso 10: los túneles se configuran pero nunca se abren. Es un
    hueco funcional anunciado en el editor de sesiones. No depende de nada. La
    biblioteca de túneles como plantillas se valora al terminarla.
- [x] **10c. Conexión que no se rinde** (juntas)
  - [[Conectar tras confirmar tarde la clave del servidor]] (`Hecha` el
    2026-09-28)
  - [[Reconexión que no se rinde tras un corte largo]] (`Hecha` el 2026-09-28)
  - Salen de dos hallazgos de las pruebas de 10b. *Juntas*: las dos tocan el
    ciclo de conexión de `SessionTab` y se prueban con el mismo servidor y el
    mismo emulador.
- [x] **10d. Ruta inicial en destinos Windows**
  - [[Ruta inicial y scripts de inicio en destinos Windows]] (`Hecha` el
    2026-09-28)
  - Encontrado por el usuario desde el Pixel: la ruta inicial y los scripts de
    inicio escribían sintaxis POSIX que `cmd.exe` no ejecutaba.
- [x] **10e. Terminal fluida** 👤 (el usuario confirma en el emulador)
  - [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]]
    (`Hecha` el 2026-09-30)
  - Encontrado por el usuario en el emulador: el centinela `__TITAN_…__` a la
    vista, líneas largas cortadas por la derecha y tirones con el teclado.
- [x] **10f. Sin rastro de la automatización en Windows**
  - [[Sin rastro de la automatización en destinos Windows]] (`Hecha` el 2026-10-01)
  - Sale de la prueba del usuario de 10e: restos del centinela al
    reengancharse a una sesión viva, el centinela del último script leído por
    `pwsh`, y el centinela visible en Windows. El primero es un fallo que
    descuadra la terminal; va primero.
  - 2026-10-01: probado por el usuario en su dispositivo; ya no aparecen los
    centinelas `__TITAN_…__`.
- [x] **10g. Detalle de las sesiones del agente**
  - [[Detalle de las sesiones del agente en el panel]] (`Hecha` el 2026-09-30)
  - Petición del usuario: ver en el panel del agente qué sesiones son
    recuperables, desde cuándo están activas y qué hay dentro. Aprobadas todas
    las ideas, solo en el panel. Independiente de 10f.
- [ ] **10h. `~` en la ruta inicial**
  - [[Expandir el ~ de la ruta inicial en destinos POSIX]]
  - Encontrado en las pruebas de 10g: `~/proyecto` falla porque la ruta va
    entre comillas simples. Pequeña; se puede hacer junto a 10f, que también
    toca la automatización de inicio.
- [x] **10i. Quitar scripts y túneles desde la ficha**
  - [[Quitar scripts y túneles desde la ficha de la sesión]] (`Hecha` el 2026-10-01)
  - Petición del usuario (2026-10-01): un `[x]` en cada fila; en un script de
    la biblioteca solo lo quita de la sesión. Hecho y probado en el emulador,
    y validado por el usuario.
- [x] **10j. Grupos como carpetas**
  - [[Grupos de hosts y de sesiones como carpetas]] (`Hecha` el 2026-10-01)
  - Petición del usuario (2026-10-01): grupos de hosts y de sesiones
    independientes y anidables, mostrados como carpetas plegables en sus
    listas y en la lanzadera. Configuración versión 3.
- [ ] **10k. Sesión mouse pad** (seguir el orden interno de
  [[Sesión mouse pad para controlar el escritorio del destino]])
  - [ ] 10k.1 [[Mouse pad en destinos Windows]]: primero la ADR; incluye la
    base común (tipo de sesión, protocolo de entrada y pestaña de gestos).
  - [ ] 10k.2 [[Mouse pad en destinos Linux X11]], solo si el usuario lo pide.
  - [ ] 10k.3 [[Mouse pad en destinos Linux Wayland]], solo si el usuario lo
    pide. 👤 El respaldo con uinput exige una preparación con root.
  - Petición del usuario (2026-10-01): controlar el ratón y el teclado del PC
    desde el móvil. Sobre todo destinos Windows, que van primero.

### Fase 5 · Distribución pública

Tiene sentido cuando el producto esté listo para usuarios ajenos (tras la
fase 4) y haya varias versiones publicadas, que SignPath e IzzyOnDroid valoran.

- [ ] **11. Flatpak en Flathub** 👤 (verificar el ID `io.github` con la cuenta de
  GitHub)
  - [[Canal Linux Flatpak en Flathub]]
  - La tarea de distribución más grande (sandbox, build desde fuente, revisión);
    va sola.
- [ ] **12. IzzyOnDroid**
  - [[Canal Android IzzyOnDroid]]
  - Reutiliza el icono y el gráfico del paso 3. Las capturas se hacen aquí; los
    metadatos fastlane servirán también para Play.
- [ ] **13. Firma de Windows con SignPath** 👤 (el usuario envía la solicitud)
  - [[Firma de código Windows con SignPath Foundation]]
  - Al acabar, winget (paso 7) pasa a apuntar al MSI firmado.
- [ ] **14. Aviso de nueva versión** 👤 (valor por defecto activado o
  desactivado)
  - [[Aviso de nueva versión en la app]]
  - Al final porque necesita conocer todos los canales para desactivarse en los
    que ya actualizan solos.

### Aparcada

- [[Desbloqueo del destino Windows con un servicio de sistema]]
  (`Planificando`): el usuario lo aparca el 2026-10-01. Para que el mouse pad
  llegue a la pantalla de bloqueo haría falta un servicio como `SYSTEM`
  instalado por un administrador. Va después de 10k.1.

- [[Revisar el contenido de la franja de estado del terminal]] (`Pendiente`):
  el usuario quiere revisarla más adelante. Por
  [[Franja de estado del terminal solo para el estado y los scripts]], la
  franja solo lleva el estado, el nivel, `reconectar` y `[>] scripts`; lo que
  tiene de más (túneles, aviso del nivel 3) no se toca hasta entonces.

- [[Indicar cuando la conexión deja de responder]] (`Pendiente`): el usuario
  prefiere retomarla cuando haya usado más la app, para dar mejores
  indicaciones sobre el umbral y el aviso. No bloquea nada. Propuesta de
  partida, sin decidir: ACK del agente en nivel 3, ping propio en niveles 1 y
  2, umbral de unos 5 s.
- [[Publicación en Google Play]] (`Planificando`): se retoma solo si el usuario
  decide pagar los 25 $. Lo que no cuesta nada (AAB y clave de subida) ya queda
  preparado en los pasos 5–6.

## Registro

- 2026-09-24 — Creada la hoja de ruta con las 25 tareas abiertas (15 de
  distribución de ADR-0011, el paraguas del nivel 3 portable con sus 6
  subtareas, y 3 más: scripts sobre el agente, scripts reutilizables y la
  verificación del agente en la APK). Ninguna completada todavía.
- 2026-09-24 — **Paso 1** hecho salvo una comprobación. Commits `fee92e8`
  (renombrado mecánico a `io.github.danielperezmartinez.titanssh` y módulo Go
  `github.com/danielperezmartinez/titan-ssh/agent`), `b528900` (dependencia de
  Gradle de los binarios del agente, que rompía la APK tras un `clean`) y
  `de7dbc4` (`LICENSE`, avisos en los README y `THIRD_PARTY_NOTICES.md`).
  Tests, APK de debug y arranque en escritorio verificados. **Falta arrancar la
  APK en el Pixel** para pasar el renombrado a `Hecha` y marcar la casilla. La
  licencia sigue `En curso` hasta la pantalla Acerca de (paso 2) y la inclusión
  en los paquetes (paso 4). El ID de Flatpak queda con dos formas válidas, a
  elegir en el paso 11.
- 2026-09-24 — **Paso 1 completado**. La APK arranca en el emulador
  `Pixel_9_Pro_XL` sin fallos y
  [[Cambiar el identificador de la app a io.github]] pasa a `Hecha`. La
  licencia sigue `En curso` por lo que tiene asignado en los pasos 2 y 4. De
  paso se corrigió la regla 2 del [[README]] (el proyecto ya está en git).
  Siguiente: paso 2.
- 2026-09-24 — Nueva tarea [[Respetar las barras del sistema en Android]],
  detectada al verificar el paso 1 en el emulador. Va junto al paso 5.
- 2026-09-24 — **Paso 2 completado** (código en el commit `8cc0482`).
  [[Versionado único desde tag de git]] pasa a `Hecha`: la versión sale de
  `-PtitanVersion`, del tag `vX.Y.Z` en HEAD o vale `0.0.0-dev`. De ella salen
  el `versionCode` (siempre creciente, también entre betas), las versiones del
  MSI y del deb, y la del agente, que ya no lleva una propia. jpackage acepta
  `0.x` en el MSI. Pantalla **Acerca de** hecha en escritorio y Android, con
  la licencia y los avisos de terceros legibles sin red.
  [[Licencia GPL-3.0-or-later del proyecto]] sigue `En curso` solo por el
  paso 4. Hallazgos para el paso 4: el JBR de Android Studio no trae
  jpackage, y conviene fijar `upgradeUuid`. En Android, el `[i]` queda en
  parte bajo la barra de estado (paso 5). Siguiente: paso 3 (icono, 👤) o,
  si el usuario aún no tiene el diseño, adelantar lo que no dependa de él.
- 2026-09-24 — Se estudió un entorno de pruebas automático para las
  conexiones entre plataformas y el usuario lo rechazó
  ([[ADR-0012 Entorno de pruebas automático multiplataforma]]): se sigue
  probando a mano, con su ayuda cuando haga falta. Queda resuelto el método de
  prueba del paso 9.4. Nueva regla 5 del [[README]]: nada sensible ni personal
  en el repositorio público.
- 2026-09-24 — **Paso 4** adelantado mientras el paso 3 (icono) avanza en otra
  sesión. Se trabajó en la rama `fase2-release`, en un worktree aparte, para
  no pisar su árbol de trabajo.
  [[Configuración de release del escritorio]] queda hecha y verificada salvo
  los iconos, así que sigue `En curso` y bloqueada por el paso 3. Resultados:
  el MSI se instala por usuario, se actualiza y se desinstala; `.deb`, `.rpm`
  y `tar.gz` se instalan y arrancan en Ubuntu, Fedora y Arch (en
  contenedores); el JRE recortado pasa los tests de SSH real y del agente. Los
  hallazgos para el CI (compilar el `.deb` en Ubuntu 22.04 y el `.rpm` en
  Fedora) están en [[Pipeline de release en GitHub Actions]]. Al integrar el
  paso 3 hay que cablear `iconFile`, el icono de la ventana y los iconos
  hicolor del `tar.gz`. Siguiente: paso 5.
- 2026-09-24 — **Paso 5** hecho en la misma rama, a falta de la parte del
  usuario. La release de Android queda con firma leída de `-P` o del
  entorno, R8 activo (8,5 MB frente a 18,7 MB de debug), sin el bloque de
  dependencias y con APK universal y AAB. En el emulador, la APK de release
  firmada con una clave desechable conecta con clave hardware, instala y
  conduce el agente, se recupera de un corte y admite una actualización sobre
  sí misma. Las barras del sistema ya no tapan nada, en vertical ni en
  horizontal, y los iconos de las barras se ven claros. Las tres tareas
  siguen `En curso` hasta que el usuario cree el keystore real (el comando
  está en [[Firma y configuración de release Android]]) y se pruebe en el
  Pixel físico. El paso 6 espera a eso y al icono.
- 2026-09-25 — **Paso 3 completado**. De tres bocetos, el usuario eligió la
  T cuyo tallo se corta y sigue en azul, como el cursor tras un microcorte
  ([[Icono de la app T que sobrevive al microcorte]]).
  [[Icono y recursos gráficos de la app]] pasa a `Hecha`: `branding/icon.svg`
  es la fuente única y `java branding/RenderIcon.java` genera el `.ico`, los
  PNG, el icono adaptativo de Android con monocromo, el icono de ventana y los
  gráficos de tienda. Verificado en el cajón de apps del emulador, en la
  ventana y la barra de tareas de Windows, y en `packageMsi`. Las capturas de
  pantalla pasan a los pasos 11 y 12. Hallazgo: si el emulador arranca con adb
  `unauthorized`, basta con aceptar el diálogo de depuración en su pantalla.
  Siguiente: pasos 4 y 5, que se pueden hacer en paralelo.
- 2026-09-26 — **Pasos 4 y 5 completados**. El usuario creó el keystore real
  (RSA 4096) y probó en el Pixel la APK de release firmada con él: clave
  hardware, nivel 3 contra [[ssh-test-host]], y la sesión sobrevive al modo
  avión. Pasan a `Hecha` [[Firma y configuración de release Android]],
  [[Verificar empaquetado del agente en APK Android]],
  [[Respetar las barras del sistema en Android]] y
  [[Licencia GPL-3.0-or-later del proyecto]], cuyo último pendiente eran los
  paquetes. El trabajo del icono estaba sin commit en `main`: se confirmó
  (`Add the app icon and generated graphics`) y la rama `fase2-release` se
  rebasó encima. Con los iconos cableados (`iconFile` del MSI y de Linux, y
  el tema hicolor en el `tar.gz`),
  [[Configuración de release del escritorio]] pasa a `Hecha`. Nueva tarea
  pequeña: [[Indicar cuando la conexión deja de responder]] (fase 4, junto
  al paso 8). Siguiente: paso 6, el pipeline y la primera pre-release.
- 2026-09-26 — **Paso 6** preparado, a falta del primer tag.
  `.github/workflows/release.yml` compila, prueba y publica el Release desde
  un tag `v*`, y con un lanzamiento manual solo compila. Además, `gradlew`
  pasa a ser ejecutable en git, y el README tiene una sección **Instalar**,
  con Obtainium y la huella del certificado. Los jobs de Linux se
  reprodujeron en Docker: los tests pasan y se generan el `.deb`, el `.rpm` y
  el `tar.gz`. Hallazgo: jpackage nunca escribe las librerías en el
  `Requires` del `.rpm`, ni compilando en Fedora, así que el `.rpm` se
  compila en Ubuntu (detalle en [[Pipeline de release en GitHub Actions]]).
  👤 Pendiente del usuario: cargar los tres secretos del keystore en GitHub,
  subir los cambios y el tag `v0.1.0-beta.1`, y probar Obtainium en el Pixel
  con `v0.1.0-beta.2` como actualización.
- 2026-09-26 — **Primera pre-release publicada**:
  [`v0.1.0-beta.1`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.1),
  con los seis jobs en verde. La APK está firmada con la clave de release, el
  `.deb` y el `.rpm` se instalan y arrancan en contenedores limpios, y los
  checksums cuadran. Fallo encontrado: GitHub cambia `~` por `.` en el nombre
  de los assets, y el `SHA256SUMS` no encontraba el `.deb` ni el `.rpm`. Ya
  está corregido en el workflow y sale con `v0.1.0-beta.2`. 👤 Falta que el
  usuario instale con Obtainium en el Pixel; después se publica
  `v0.1.0-beta.2` como actualización.
- 2026-09-26 — El usuario **decide no usar Obtainium** en su móvil. Instaló la
  APK de `v0.1.0-beta.1` descargándola del Release, encima de la que tenía:
  conserva los datos, muestra el icono y funciona bien.
  [[Canal Android Obtainium desde GitHub Releases]] pasa a `Hecha`, y
  Obtainium queda documentado para quien lo quiera. El paso 14 (aviso de
  versión) no se adelanta, también por decisión suya. Se publica
  `v0.1.0-beta.2` para probar una actualización con `versionCode` mayor y el
  arreglo de los nombres con `~`.
- 2026-09-26 — **Paso 6 completado. Fin de la fase 2.**
  [`v0.1.0-beta.2`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.2)
  sale con todos los jobs en verde, y `sha256sum -c SHA256SUMS` da `OK` en
  todos los ficheros. El usuario la instaló en el Pixel encima de la `beta.1`
  (`versionCode` 10031 → 10032): conserva los datos y funciona bien.
  [[Pipeline de release en GitHub Actions]] pasa a `Hecha`. Ya no se pasa la
  APK a mano. Para cada versión basta con crear el tag en `main` y subirlo.
  Siguiente: paso 7 (AUR y winget, 👤).
- 2026-09-26 — **Paso 7** preparado, a falta de la parte del usuario. Por
  decisión suya, las pre-releases van a un canal aparte en los dos gestores:
  `titan-ssh-beta-bin` en AUR y `DanielPerezMartinez.TitanSSH.Beta` en
  winget. Las estables irán a `titan-ssh-bin` y `DanielPerezMartinez.TitanSSH`.
  El workflow tiene dos jobs nuevos tras `publish`: `aur` construye el
  PKGBUILD a partir de la plantilla de `desktopApp/packaging/aur/` y lo sube
  por SSH, y `winget` abre el PR en winget-pkgs con Komac. El paquete de AUR
  se instala, actualiza, arranca y desinstala en un Arch limpio. Los
  manifiestos de la beta.2 para el primer envío a winget pasan `winget
  validate`, y winget-pkgs acepta el MSI sin firmar. 👤 Pendiente: cuenta de
  AUR y clave SSH, fork de winget-pkgs, token, primer PR con el CLA y los dos
  secretos. Después, la siguiente beta prueba los dos jobs y se comprueba
  `yay` en el Arch del usuario y `winget` en Windows. Detalle en
  [[Canal Arch Linux AUR]] y [[Canal Windows winget]].
- 2026-09-26 — **AUR, a la espera**: AUR tiene cerrado el registro de cuentas
  nuevas, sin fecha de reapertura. La clave SSH ya está en GitHub.
  [[Canal Arch Linux AUR]] pasa a `Pendiente` con los pasos para retomarla, y
  sale del paso 7 a la nueva sección **A la espera de terceros**. Los jobs
  `aur` y `winget` solo se ejecutan si las variables del repositorio
  `AUR_ENABLED` o `WINGET_ENABLED` valen `true`, para que las releases no
  fallen mientras tanto. **winget**: fork creado y primer PR abierto,
  [microsoft/winget-pkgs#441824](https://github.com/microsoft/winget-pkgs/pull/441824).
  👤 Faltan el CLA, la fusión, el token y la variable `WINGET_ENABLED`.
- 2026-09-26 — Nueva tarea [[Revisión diaria de winget y AUR]], pedida por el
  usuario: cada día se comprueba si Microsoft ha fusionado el PR de winget y
  si AUR ha reabierto el registro. La tarea recoge los pasos que faltan en
  los dos canales. El paso 7 queda a la espera de terceros sin bloquear la
  fase 4. Siguiente: paso 8.
- 2026-09-26 — **Paso 8**, primera parte:
  [[Scripts de inicio por sesión sobre el agente]] pasa a `Hecha`. Las sesiones
  de nivel 3 ejecutan el `cd` inicial y los scripts de conexión solo cuando
  `titan-agent` crea un PTY nuevo. Al reengancharse a uno vivo (tras un corte
  o al reabrir la app) no se repite nada. El agente lo indica con un flag
  nuevo en `HELLO_OK`, compatible en los dos sentidos (nota en
  [[ADR-0008 Diseño del agente de resiliencia nivel 3]]). De paso se corrigió
  que, si la sesión del agente se perdía (reinicio del destino), la pestaña
  descartaba toda la salida del PTY nuevo. Verificado con tests y contra
  [[ssh-test-host]]. El cambio del agente sale en la siguiente pre-release.
  Queda la tarea pequeña del paso,
  [[Indicar cuando la conexión deja de responder]].
- 2026-09-27 — **Paso 8 completado.** El usuario aplaza
  [[Indicar cuando la conexión deja de responder]] hasta haber usado más la
  app, y pasa a **Aparcada** (sigue `Pendiente`). Siguiente: paso 9.1
  ([[titan-agent PTY propio multiplataforma]]), o 9.2, que es independiente.
- 2026-09-27 — **Paso 9.1 completado**:
  [[titan-agent PTY propio multiplataforma]] pasa a `Hecha`. El agente ya no
  depende de `creack/pty`. Tiene PTY propio en Linux, macOS y FreeBSD, y
  ConPTY en Windows, y su única dependencia es `golang.org/x/sys`, que sube
  `go.mod` a `go 1.26`. Los tests de contrato pasan en Windows y en Linux
  (Docker, `-race`), y el binario real funciona de punta a punta en un
  contenedor. macOS y FreeBSD solo se han compilado. El host de pruebas no
  respondía, así que no se probó ahí. El cambio sale en la siguiente
  pre-release. Siguiente: 9.2 y 9.3, juntas
  ([[titan-agent instancia única y directorio de estado]] y
  [[titan-agent punto de encuentro TCP loopback con token]]).
- 2026-09-27 — **Pasos 9.2 y 9.3 completados**, juntos:
  [[titan-agent instancia única y directorio de estado]] y
  [[titan-agent punto de encuentro TCP loopback con token]] pasan a `Hecha`.
  El agente ya no usa sockets Unix. Hay un solo daemon por usuario, gracias a
  un candado en `~/.local/state/titan-ssh` o `%LOCALAPPDATA%\titan-ssh`, y el
  front conecta con él por TCP en `127.0.0.1` con un token de `agent.json`.
  Novedades: `--stop` y los errores `E_STATE_DIR`, `E_LOCK`,
  `E_DAEMON_START` y `E_AUTH`. Sobre el diseño, se añadió un ack del daemon al
  preámbulo. Tests verdes en Windows y en Linux (Docker, `-race`). El host de
  pruebas no respondía, así que la prueba de punta a punta se hizo con un
  sshd en Docker: pasan los tests de integración Kotlin del agente con el
  binario real, y ocho fronts a la vez dejan un solo daemon. Al actualizar la
  app, las sesiones de un daemon anterior se pierden una vez (ver la
  migración en la nota de 9.3). La limpieza de binarios antiguos pasa a 9.5.
  El cambio sale en la siguiente pre-release. Siguiente: 9.4
  ([[titan-agent daemon en Windows]], 👤), o avanzar la parte de 9.5 que no
  necesita Windows.
- 2026-09-27 — **Paso 9.4** hecho, a falta de la limpieza del usuario:
  [[titan-agent daemon en Windows]]. El front mira los flags de su Job Object
  y lanza el daemon con `CREATE_BREAKAWAY_FROM_JOB`. Si el job mata al
  cerrarse y no deja salir, sale con `E_JOB_NO_BREAKAWAY`. Probado con un Job
  Object real en los tests y a mano por SSH con un usuario estándar temporal
  contra el sshd local: la sesión sobrevive al cierre limpio y al corte brusco,
  dos fronts dejan un solo daemon y `--stop` lo termina. 👤 Falta que el
  usuario borre la cuenta `titantest` (reiniciando antes `sshd`) para pasar la
  tarea a `Hecha`.
- 2026-09-27 — **Paso 9.5** hecho, a falta de verlo en CI y de la limpieza:
  [[Instalación del agente en destinos Windows y multi-SO]]. Decisiones del
  usuario: los binarios que no van en la app se descargan del **GitHub
  Release** (que publica los 13), y la prueba en Windows reutiliza la cuenta
  estándar temporal de 9.4.
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]] pasa a
  `Aceptada`.
  - El instalador sube el agente por SFTP en Windows y en Unix, detecta los
    13 destinos y pone las comillas de sh, cmd o PowerShell. Verifica el
    SHA-256, no resube lo que ya está y borra las versiones anteriores.
  - La app lleva seis binarios y el SHA-256 de los 13, y descarga el resto
    del Release de su versión.
  - En `release.yml`, un job `agent` compila los binarios una vez para todos
    los paquetes y para el Release.
  - Probado de punta a punta en Windows con un usuario estándar, y en Linux
    amd64 (con y sin SFTP) y en arm y riscv64 emulados con QEMU.
  - Dos arreglos encontrados al probar: la escritura SFTP se trocea (sshj
    desbordaba la ventana del canal) y un `ACK` con el canal ya cerrado se
    descarta.
  - Pendiente: ver el job `agent` y los assets en la siguiente pre-release, y
    👤 reiniciar `sshd` y borrar la cuenta de pruebas, que cierra también 9.4.
    Siguiente: 9.6 ([[Diagnóstico cuando el nivel 3 no está disponible]]).
- 2026-09-27 — **Paso 9.4 completado.** El usuario decide **conservar** la
  cuenta estándar de pruebas de Windows para repetir este tipo de pruebas, en
  vez de borrarla. Su perfil ya no tiene binarios ni estado del agente, así que
  [[titan-agent daemon en Windows]] pasa a `Hecha`, y 9.5 ya solo espera a ver
  el job `agent` en CI. De paso, `docs/.obsidian/workspace.json` (la
  disposición de ventanas de Obsidian de cada equipo) sale de git y pasa al
  `.gitignore`.
- 2026-09-27 — **CI del paso 9.5**: un `workflow_dispatch` (run
  `36310475296`) pasa entero con el job `agent` nuevo. Solo falta ver los 13
  binarios publicados en la siguiente pre-release, sin prisa: no bloquea nada.
  Además, en el PC de desarrollo, el `sshd` que sirve de destino Windows se
  mudó a `C:\Program Files\OpenSSH`. Antes corría como SYSTEM desde una
  carpeta en la que cualquier usuario podía escribir. Sigue arrancando solo con
  el PC. **Siguiente sesión: paso 9.6**
  ([[Diagnóstico cuando el nivel 3 no está disponible]]).
- 2026-09-27 — **Paso 9.6 completado**:
  [[Diagnóstico cuando el nivel 3 no está disponible]] pasa a `Hecha`. Si el
  nivel 3 no se puede usar, la pestaña ya no degrada en silencio. La franja de
  estado muestra el nivel efectivo (agente, tmux/screen o base) y, debajo, el
  motivo y qué hacer. El motivo se recuerda durante la vida de la pestaña, así
  que las reconexiones no reintentan la instalación. En Linux con systemd,
  `KillUserProcesses=yes` sin linger da un aviso con la acción **activar
  linger**. Hueco encontrado de paso: los fallos de PTY del daemon (`E_PTY`,
  `E_NO_CONPTY`) se perdían. Ahora viajan en un `BYE` con motivo, compatible en
  los dos sentidos. Verificado con tests (Go en Windows y en Linux con `-race`,
  159 tests de escritorio) y de punta a punta contra un sshd en Docker
  (`E_STATE_DIR`, `E_NO_BINARY`, el aviso de systemd y activar linger). El
  cambio del agente y de la app sale en la siguiente pre-release. El paso 9 solo
  espera ya a ver los binarios de 9.5 publicados en esa pre-release.
  Siguiente: paso 10 ([[Scripts y túneles reutilizables de primera clase]],
  👤). Antes de tocar código hay que decidir con el usuario si se unifican con
  los Snippets (posible ADR). No hay nada técnico que lo bloquee. Aparte, y
  cuando el usuario lo pida, conviene publicar `v0.1.0-beta.3`: cierra 9.5 (los
  13 binarios en el Release) y trae 9.6 a la app instalada para verlo en el
  Pixel.
- 2026-09-27 — **Paso 9 completado. Publicada
  [`v0.1.0-beta.3`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.3)**,
  a petición del usuario. Todos los jobs del run `36322431700` acaban bien
  (`winget` y `aur` se saltan, porque sus variables siguen desactivadas). El
  Release está marcado como pre-release, `sha256sum -c SHA256SUMS` da `OK` en los
  19 ficheros y la APK está firmada con la clave de release (huella del
  `README`). Lleva los 13 binarios del agente, que coinciden con los SHA-256
  fijados en la app. La descarga bajo demanda funciona: la app bajó del Release
  el binario riscv64 y condujo una sesión con él en un sshd emulado en Docker.
  [[Instalación del agente en destinos Windows y multi-SO]] y
  [[Nivel 3 portable a todos los destinos]] pasan a `Hecha`. 👤 Falta que el
  usuario instale la APK en el Pixel desde el Release y confirme que funciona.
  Con ella se ve también el aviso del nivel 3 de 9.6. Siguiente: paso 10 (👤,
  decidir si los scripts se unifican con los Snippets).
- 2026-09-27 — **Paso 10** hecho, a falta de probarlo en la app real. Al revisar
  el modelo se vieron tres cosas. Los snippets solo se copiaban en el script.
  Los scripts bajo demanda no se podían lanzar desde ningún sitio. Y los
  túneles se guardaban pero nunca se abrían. Decisiones del usuario
  ([[ADR-0013 Biblioteca de scripts unificada con los snippets]]):
  - Los snippets se unifican en una biblioteca de scripts (pestaña Scripts).
  - Las sesiones los usan por referencia viva y pueden tener también scripts
    propios.
  - La pestaña de la sesión tiene un menú `[>] scripts`.
  - Los túneles salen a una tarea nueva,
    [[Ejecutar los túneles de las sesiones]] (paso 10b).

  La config sube a la versión 2 con una migración que no cambia lo que se
  ejecuta y guarda una copia del fichero anterior. De paso, el selector de fase
  ya no se aplasta en el móvil. Verificado con 172 tests y con un render
  offscreen de las pantallas y del menú sobre una shell falsa.
  [[Scripts y túneles reutilizables de primera clase]] sigue `En curso`: 👤
  falta probar el flujo en la app real (los pasos están en la tarea). Sale en
  la siguiente pre-release.
- 2026-09-27 — **Paso 10, prueba en Android.** El usuario pide que Android se
  pruebe antes de publicar sin Release ni workflow: capturas del emulador y el
  emulador abierto para que él lo pruebe. Queda como paso 0 de la regla 4 del
  [[README]]. En el emulador, la migración de una config de versión 1 funcionó.
  Apareció un fallo que ya estaba antes: **en Android no se ejecutaba ningún
  script**, por una `}` sin escapar en una expresión regular que el motor de
  Android rechaza. Corregido. El selector de fase pasa a scroll lateral, a la
  espera de que el usuario elija tras verlo. 👤 Falta su prueba en el emulador.
- 2026-09-27 — **Paso 10 completado**:
  [[Scripts y túneles reutilizables de primera clase]] pasa a `Hecha` tras la
  prueba del usuario en el emulador. El scroll lateral lo había pedido pensando
  en las pestañas (Hosts, Sesiones…), así que el selector de fase vuelve a
  saltar de línea. Sale en la siguiente pre-release. Siguiente: paso 10b
  ([[Ejecutar los túneles de las sesiones]]).
- 2026-09-27 — Nueva tarea, pedida por el usuario y hecha en la misma sesión:
  [[Scroll lateral en las pestañas de Configuración]]. Las barras de pestañas
  (las de Configuración y la de arriba, Configuración / Sesiones) se desplazan
  en horizontal si no caben, en vez de partir las etiquetas. Verificado en el
  emulador con pantalla y letra grandes. Siguiente: paso 10b.
- 2026-09-27 — Entorno de pruebas: el servidor SSH de pruebas se conserva, como
  la cuenta de Windows de 9.4. Su imagen está en `tools/test-sshd/` (usuario y
  contraseña `demo`, solo en `127.0.0.1:2222`, `10.0.2.2:2222` desde el
  emulador) y el contenedor solo se enciende y se apaga. Los ficheros de
  Obsidian propios de cada equipo (disposición de ventanas y plugins de la
  comunidad) pasan al `.gitignore`; la configuración compartida de la bóveda
  (Bases, plantillas) sigue en git.
- 2026-09-27 — **Paso 10b** hecho, a falta del usuario:
  [[Ejecutar los túneles de las sesiones]]. Los túneles local, remoto y SOCKS
  (4, 4a y 5) se abren sobre la conexión SSH de la pestaña en los tres niveles.
  Se reabren tras un microcorte y se cierran con la pestaña. La franja de estado
  muestra `[=] túneles activos/total` y un panel con el motivo de cada fallo. Un
  túnel con el puerto ocupado, aquí o en el servidor, se reintenta solo. Hace
  falta porque, tras un corte, el servidor puede retener un rato el puerto del
  túnel remoto. Verificado con 192 tests, con tests de integración contra el
  servidor de pruebas (que pasa a tener el reenvío activado) y en el emulador
  con tráfico real y un corte en modo avión. 👤 Falta que el usuario lo pruebe
  en el emulador (sesión "proyecto demo", con cinco túneles de ejemplo) y que
  confirme la propuesta para Android en segundo plano: el túnel vive lo que viva
  la conexión, sin servicio en primer plano. Hallazgo aparte: con 40 s sin red,
  la pestaña agota sus 6 reintentos y se queda caída. Sale en la siguiente
  pre-release.
- 2026-09-28 — **Paso 10b completado**:
  [[Ejecutar los túneles de las sesiones]] pasa a `Hecha`. En Android, el túnel
  dura lo que dure la conexión de la pestaña. Mantener sesiones y túneles vivos
  con la app oculta necesitaría un servicio en primer plano, que sería una tarea
  aparte. La prueba en el emulador cubre la verificación en dispositivo. Con
  esto se cierra la fase 4. Siguiente: la fase 5, cuando el usuario quiera abrir
  la distribución pública. Antes conviene publicar una pre-release con los
  pasos 10 y 10b.
- 2026-09-28 — **Paso 10c completado**, a partir de dos hallazgos de 10b
  (uno estaba en una nota suelta, `Revisar.md`, que se sustituye por su
  tarea):
  - [[Conectar tras confirmar tarde la clave del servidor]]: la pregunta de
    la clave de host sigue en pantalla aunque caduque el saludo SSH (sshj corta
    a los 30 s), y al aceptarla la pestaña vuelve a conectar sola.
  - [[Reconexión que no se rinde tras un corte largo]]: se reintenta durante
    15 minutos en vez de unos 25 s, Android reconecta en cuanto vuelve la red,
    y la franja ofrece **reconectar**, también en una pestaña caída o
    fallida, sin perder el historial.

  Verificado con 203 tests, un test de integración nuevo contra el servidor de
  pruebas y en el emulador con el modo avión. Hallazgo anotado en
  [[Indicar cuando la conexión deja de responder]] (sigue aparcada): sin red,
  la pestaña tarda unos 3 minutos en darse cuenta del corte. Sale en la
  siguiente pre-release, junto con 10 y 10b. Siguiente: publicar esa
  pre-release cuando el usuario lo pida, o la fase 5.
- 2026-09-28 — **Publicada
  [`v0.1.0-beta.4`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.4)**,
  a petición del usuario, con los pasos 10, 10b y 10c. Todos los jobs del run
  `36357628662` acaban bien (`aur` y `winget` se saltan, porque sus variables
  siguen desactivadas). El Release está marcado como pre-release,
  `sha256sum -c SHA256SUMS` da `OK` en los 19 ficheros y la APK está firmada
  con la clave de release (huella del `README`). 👤 Falta que el usuario
  instale la APK en el Pixel desde el Release y confirme que funciona.
  Siguiente: la fase 5, cuando el usuario quiera abrir la distribución
  pública.
- 2026-09-28 — El usuario instaló la APK de `v0.1.0-beta.4` en el Pixel desde
  el Release y confirma que funciona. Queda cerrada la comprobación del
  Release (paso 6 de la regla 4 del [[README]]). Siguiente: la fase 5, cuando
  el usuario quiera abrir la distribución pública.
- 2026-09-28 — **Paso 10d** abierto y arreglado: la ruta inicial y los scripts
  de inicio no funcionaban contra Windows (sintaxis POSIX y `\n` como Enter).
  Verificado con tests y en el emulador contra el `sshd` de Windows del PC de
  desarrollo (niveles 1 y 3). El usuario lo probó en el emulador y confirma
  que funciona: tarea `Hecha`.
- 2026-09-28 — **Paso 10e** abierto: centinela a la vista, líneas cortadas y
  tirones en la terminal. La fuente mono no llegaba al APK de Android. Terminal
  rehecha (reajuste de líneas, pintado en Canvas, resize con espera) y
  centinela oculto. Verificado con tests y en el emulador; falta la
  confirmación del usuario.
- 2026-09-28 — **Publicada
  [`v0.1.0-beta.5`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.5)**,
  a petición del usuario, con los pasos 10d y 10e. Todos los jobs del run
  `36401870388` acaban bien (`aur` y `winget` se saltan, porque sus variables
  siguen desactivadas). El Release está marcado como pre-release,
  `sha256sum -c SHA256SUMS` da `OK` en los 19 ficheros, la APK está firmada
  con la clave de release (huella del `README`) y lleva la fuente en
  `assets/composeResources`. 👤 Falta que el usuario instale la APK en el Pixel
  desde el Release y confirme que funciona, y que confirme la terminal del
  paso 10e.
- 2026-09-29 — **Publicada
  [`v0.1.0-beta.6`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.6)**,
  a petición del usuario, con
  [[Quitar las pestañas de área y abrir Configuración desde la cabecera]] y
  [[Acciones contextuales en las filas de lista]]. Todos los jobs del run
  `36605549296` acaban bien (`aur` y `winget` se saltan, porque sus variables
  siguen desactivadas). El Release está marcado como pre-release,
  `sha256sum -c SHA256SUMS` da `OK` en los 19 ficheros y la APK está firmada
  con la clave de release (huella del `README`). 👤 Falta que el usuario
  instale la APK en el Pixel desde el Release y confirme que funciona.
- 2026-09-29 — El usuario instaló la APK de `v0.1.0-beta.6` en el Pixel desde
  el Release y confirma que funciona. Queda cerrada la comprobación del
  Release. El clic derecho de escritorio de
  [[Acciones contextuales en las filas de lista]] sigue sin probar; el usuario
  la da por cerrada igualmente.
- 2026-09-30 — **Publicada
  [`v0.1.0-beta.7`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.7)**,
  a petición del usuario, con
  [[Transparencia y control del agente en el destino]] (sesiones sin
  caducidad, órdenes de control del agente y panel del agente) y el acceso a
  Configuración desde la lanzadera vacía. Todos los jobs del run
  `36750827348` acaban bien (`aur` y `winget` se saltan, porque sus variables
  siguen desactivadas). El Release está marcado como pre-release,
  `sha256sum -c SHA256SUMS` da `OK` en los 19 ficheros y la APK está firmada
  con la clave de release (huella del `README`). 👤 Falta que el usuario
  instale la APK en el Pixel desde el Release y confirme que funciona.
- 2026-09-30 — El usuario probó la APK de `v0.1.0-beta.7` y confirma que
  funciona, incluido "Actualizar" un agente de otra versión. Queda cerrada la
  comprobación del Release y la tarea
  [[Transparencia y control del agente en el destino]].
- 2026-09-30 — **Paso 10e completado**. El usuario probó la beta.7 en el Pixel
  contra un destino Windows y confirma la fluidez y el ajuste de líneas;
  [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]]
  pasa a `Hecha` por decisión suya. En esa prueba salieron tres problemas del
  centinela en Windows, que pasan a la nueva
  [[Sin rastro de la automatización en destinos Windows]] (paso 10f). También
  pidió ver más datos de las sesiones en el panel del agente:
  [[Detalle de las sesiones del agente en el panel]] (paso 10g), en
  `Planificando` hasta que elija qué datos quiere.
- 2026-09-30 — Nueva regla del usuario:
  [[Franja de estado del terminal solo para el estado y los scripts]]. La
  franja de encima del terminal solo lleva el estado de la conexión, el nivel
  de resiliencia y `[>] scripts`. Lo que tiene de más se revisará en
  [[Revisar el contenido de la franja de estado del terminal]] (**Aparcada**).
  En [[Detalle de las sesiones del agente en el panel]] (paso 10g) el usuario
  aprueba todas las ideas, solo en el panel del agente; pasa a `Pendiente`.
- 2026-09-30 — **Paso 10g hecho, a falta del visto bueno del usuario**
  ([[Detalle de las sesiones del agente en el panel]]). El agente informa por
  sesión de la shell, el programa en marcha, el directorio (en Linux), el
  tamaño, la última salida, el título, la CPU, y tiene la orden `--preview`.
  El panel lo muestra al desplegar cada sesión, con una vista previa de la
  terminal ([[Detalle de las sesiones en el panel del agente]], `Propuesta`).
  Probado con tests (Go en Windows y en Linux con `-race`, 246 de Kotlin), de
  extremo a extremo contra el contenedor y en el emulador. 👤 Falta que el
  usuario apruebe cómo se reparte el detalle.
- 2026-09-30 — **Publicada
  [`v0.1.0-beta.8`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.8)**,
  a petición del usuario, con [[Detalle de las sesiones del agente en el panel]]
  (paso 10g). Todos los jobs del run `36766264534` acaban bien (`aur` y
  `winget` se saltan, porque sus variables siguen desactivadas). El Release
  está marcado como pre-release, `sha256sum -c SHA256SUMS` da `OK` en los 19
  ficheros y la APK está firmada con la clave de release (huella del
  `README`). 👤 Falta que el usuario instale la APK en el Pixel desde el
  Release y confirme que funciona. Para ver los datos nuevos en un destino,
  su agente tiene que ser de la beta.8, y actualizarlo cierra sus sesiones.
- 2026-09-30 — El usuario probó la APK de `v0.1.0-beta.8` en el Pixel 9 y
  confirma que funciona bien. [[Detalle de las sesiones del agente en el panel]]
  (paso 10g) pasa a `Hecha`.
- 2026-10-01 — **Paso 10f** hecho:
  [[Sin rastro de la automatización en destinos Windows]]. El reenganche a un
  destino Windows ya no descuadra la terminal, el último paso de la cadena va
  sin centinela (un último `pwsh` ya no caduca) y en Windows el centinela se
  vacía en su sitio, dejando un hueco en blanco. Probado en el emulador contra
  el sshd de Windows y el contenedor de pruebas.
- 2026-10-01 — **Publicada
  [`v0.1.0-beta.9`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.9)**,
  con [[Sin rastro de la automatización en destinos Windows]] (paso 10f),
  [[Quitar scripts y túneles desde la ficha de la sesión]] (paso 10i) y
  [[Grupos de hosts y de sesiones como carpetas]] (paso 10j). El run
  `36789594455` acaba bien.
- 2026-10-01 — El usuario probó la APK de `v0.1.0-beta.9` y confirma que ya no
  aparecen los centinelas `__TITAN_…__` en Windows y que la eliminación/desvinculación
  de scripts y túneles funciona bien. [[Sin rastro de la automatización en destinos Windows]]
  (paso 10f) y [[Quitar scripts y túneles desde la ficha de la sesión]] (paso 10i)
  pasan a `Hecha`.
- 2026-10-01 — Nueva petición del usuario: una sesión **mouse pad** para
  controlar el ratón y el teclado del destino desde el móvil (paso 10k).
  Tarea paraguas [[Sesión mouse pad para controlar el escritorio del destino]]
  con tres subtareas: Windows (primero, con la ADR y la base común), Linux
  X11 y Linux Wayland. El usuario acepta en Windows una tarea programada
  visible para lanzar el ayudante en su escritorio.
- 2026-10-01 — **Paso 10k.1** implementado en la rama
  `worktree-mousepad-tasks`, con
  [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]] aceptada y
  [[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]
  propuesta (👤 falta que el usuario la acepte). Funciona de punta a punta por
  SSH y desde el emulador: movimiento, clics, arrastre y teclado Unicode con
  atajos. 👤 Falta que el usuario pruebe los gestos de dos dedos, el teclado
  del móvil y el PC bloqueado. Se aparca
  [[Desbloqueo del destino Windows con un servicio de sistema]].

