---
Nombre: 'Seguimiento de tareas pendientes'
Estado: 'En curso'
Resumen: 'Hoja de ruta viva con todas las tareas abiertas del proyecto, ordenadas por prioridad, para irlas completando una a una en sesiones sucesivas nombrando esta nota. Indica qué tareas conviene hacer juntas en la misma sesión y qué pasos necesitan una acción del usuario. Orden: primero lo que hay que hacer con el árbol de trabajo limpio y antes de publicar (cambio de identificador y licencia); después la base de release hasta la primera pre-release con Obtainium, que sustituye a pasar la APK a mano; los canales propios del usuario (AUR, winget); el hueco funcional de scripts sobre el agente; el nivel 3 portable; los scripts reutilizables; y al final los canales públicos (Flathub, IzzyOnDroid), la firma de SignPath y el aviso de versión. Google Play queda aparcado.'
Decisiones: 'Prioridad acordada el 2026-09-24 a partir de [[ADR-0011 Distribución y canales de publicación]] y [[ADR-0009 Agente de nivel 3 portable a todos los destinos]]. El nivel 3 portable conserva su propio orden interno en [[Nivel 3 portable a todos los destinos]].'
Bloqueada: []
Fecha de creación: 2026-09-24T00:05:00+02:00
Última modificación: 2026-09-24T12:00:00+02:00
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

- [ ] **1. Identificador y licencia** (juntas)
  - [[Cambiar el identificador de la app a io.github]]
  - [[Licencia GPL-3.0-or-later del proyecto]] (el fichero `LICENSE`, los
    avisos y el README; la pantalla Acerca de puede esperar al paso 2)
  - *Por qué primero*: el renombrado de paquetes toca unos 100 ficheros Kotlin
    y choca con cualquier trabajo en curso. Hay que hacerlo con el árbol limpio,
    antes de que el resto de tareas añada código bajo `im/gar/titanssh`. El
    `applicationId` queda fijado para siempre en la primera versión pública.
    La licencia es corta y comparte el mismo commit de "preparar el repositorio".
- [ ] **2. Versionado único** + pantalla Acerca de
  - [[Versionado único desde tag de git]]
  - La parte de UI de [[Licencia GPL-3.0-or-later del proyecto]] (Acerca de, con
    versión y avisos legales), que necesita la versión.
- [ ] **3. Icono** 👤 (el usuario decide el diseño; se puede hacer en paralelo a
  1–2)
  - [[Icono y recursos gráficos de la app]]

### Fase 2 · Primera pre-release (deja de pasarse la APK a mano)

- [ ] **4. Release de escritorio**
  - [[Configuración de release del escritorio]]
  - Depende de 1–3.
- [ ] **5. Release de Android y agente en la APK** (juntas) 👤 (el usuario crea
  y custodia el keystore)
  - [[Firma y configuración de release Android]]
  - [[Verificar empaquetado del agente en APK Android]]
  - *Juntas*: las dos se verifican con la misma APK en el Pixel. Conviene
    comprobar que los binarios del agente sobreviven a R8 y a la build de
    release, no solo a la de debug.
  - Depende de 1–3. Se puede hacer en paralelo a 4.
- [ ] **6. Pipeline y Obtainium** (juntas) — **hito: primera pre-release**
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
- [ ] **9. Nivel 3 portable a todos los destinos** (seguir el orden interno de
  [[Nivel 3 portable a todos los destinos]])
  - [ ] 9.1 [[titan-agent PTY propio multiplataforma]]
  - [ ] 9.2 [[titan-agent instancia única y directorio de estado]] (independiente
    de 9.1)
  - [ ] 9.3 [[titan-agent punto de encuentro TCP loopback con token]] (tras 9.2;
    *juntas*: 9.2 y 9.3 tocan el mismo directorio de estado y el arranque
    del daemon, así que pueden ir en una misma sesión)
  - [ ] 9.4 [[titan-agent daemon en Windows]] 👤 (el método de prueba en Windows
    está por decidir con el usuario) (tras 9.1 y 9.3)
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
  - Reutiliza los textos y capturas del icono (paso 3); los metadatos fastlane
    servirán también para Play.
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
