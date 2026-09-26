---
Nombre: 'Seguimiento de tareas pendientes'
Estado: 'En curso'
Resumen: 'Hoja de ruta viva con todas las tareas abiertas del proyecto, ordenadas por prioridad, para irlas completando una a una en sesiones sucesivas nombrando esta nota. Indica qué tareas conviene hacer juntas en la misma sesión y qué pasos necesitan una acción del usuario. Orden: primero lo que hay que hacer con el árbol de trabajo limpio y antes de publicar (cambio de identificador y licencia); después la base de release hasta la primera pre-release con Obtainium, que sustituye a pasar la APK a mano; los canales propios del usuario (AUR, winget); el hueco funcional de scripts sobre el agente; el nivel 3 portable; los scripts reutilizables; y al final los canales públicos (Flathub, IzzyOnDroid), la firma de SignPath y el aviso de versión. Google Play queda aparcado.'
Decisiones: 'Prioridad acordada el 2026-09-24 a partir de [[ADR-0011 Distribución y canales de publicación]] y [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. El nivel 3 portable conserva su propio orden interno en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-24T00:05:00+02:00
Última modificación: 2026-09-26T18:46:00+02:00
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

- [ ] **7. AUR y winget** (juntas) 👤 (el usuario crea la cuenta de AUR con su
  clave SSH y un token de GitHub para el fork de `winget-pkgs`)
  - [[Canal Arch Linux AUR]]
  - [[Canal Windows winget]]
  - *Juntas*: los dos son un job más del pipeline que publica un manifiesto a
    partir del Release, con el mismo patrón de secretos. Son pequeños.
  - Hasta tener Flatpak (paso 11), en Bazzite se usa el `tar.gz` del Release.

### Fase 4 · Producto

- [ ] **8. Scripts de inicio en sesiones del agente**
  - [[Scripts de inicio por sesión sobre el agente]]
  - *Por qué aquí*: es un hueco en un pilar del producto (la automatización
    gratuita): hoy una sesión de nivel 3 no ejecuta el `cd` inicial ni los
    scripts (`SessionTab` retorna por la ruta de `AgentTransport` antes de
    `StartScriptAutomation`). Es independiente y pequeño comparado con el nivel
    3 portable.
  - Tarea pequeña que puede ir junto a este paso:
    [[Indicar cuando la conexión deja de responder]]. Salió en la prueba del
    paso 5: durante un microcorte la pestaña sigue en "Conectado" sin avisar
    de nada.
- [ ] **9. Nivel 3 portable a todos los destinos** (seguir el orden interno de
  [[Nivel 3 portable a todos los destinos]])
  - [ ] 9.1 [[titan-agent PTY propio multiplataforma]]
  - [ ] 9.2 [[titan-agent instancia única y directorio de estado]] (independiente
    de 9.1)
  - [ ] 9.3 [[titan-agent punto de encuentro TCP loopback con token]] (tras 9.2;
    *juntas*: 9.2 y 9.3 tocan el mismo directorio de estado y el arranque
    del daemon, así que pueden ir en una misma sesión)
  - [ ] 9.4 [[titan-agent daemon en Windows]] 👤 (prueba manual en Windows:
    el usuario crea el usuario estándar temporal) (tras 9.1 y 9.3)
  - [ ] 9.5 [[Instalación del agente en destinos Windows y multi-SO]] 👤 (cerrar
    el origen de descarga de
    [[ADR-0010 Empaquetado del agente y descarga bajo demanda]]; con el
    repositorio público y el pipeline del paso 6, GitHub Releases es el
    candidato natural. Si se elige, ampliar el pipeline para publicar los
    binarios)
  - [ ] 9.6 [[Diagnóstico cuando el nivel 3 no está disponible]] (tras 9.3–9.5)
  - Cada subtarea que cambie el agente sale en la siguiente pre-release, y así se
    prueba desde Obtainium o la app instalada.
- [ ] **10. Scripts y túneles reutilizables** 👤 (antes de tocar el modelo hay
  que decidir con el usuario si se unifican con los Snippets; posible ADR)
  - [[Scripts y túneles reutilizables de primera clase]]
  - *Por qué después del nivel 3*: es una mejora de organización de algo que ya
    funciona (scripts embebidos en cada sesión), mientras que el nivel 3
    portable completa la resiliencia, el pilar principal. Si el usuario lo
    prefiere antes, se puede adelantar: no depende de nada.

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
