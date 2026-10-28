# Memoria del proyecto

Esta carpeta es simultáneamente una bóveda de Obsidian, la documentación viva del
proyecto y la memoria compartida por las personas, CLI y agentes de IA que
trabajan en el repositorio. Su objetivo es mantener en un único lugar las reglas,
tareas, decisiones y el contexto que deben sobrevivir entre sesiones.

El punto de entrada humano es [[Inicio]]. Este archivo es el punto de entrada
obligatorio para agentes.

## Protocolo obligatorio para agentes

Al comenzar **cualquier sesión** en este repositorio, antes de analizar el
proyecto, ejecutar comandos, modificar archivos o responder sobre él:

1. Leer íntegramente este `README.md`.
2. Abrir [[Inicio]] para conocer los sistemas de documentación disponibles.
3. Consultar el sistema relacionado con la tarea actual. Revisar primero las
   propiedades y resúmenes en el panel `.base` correspondiente y abrir la nota
   completa cuando sea relevante.
4. Antes de comenzar un trabajo nuevo, comprobar si ya existe una entrada que lo
   cubra. Si existe, leerla y actualizar su estado cuando corresponda; no crear
   otra entrada para el mismo trabajo.
5. Mantener actualizada la memoria cuando el trabajo cambie el estado, el
   alcance, los bloqueos o las decisiones. Al terminar, actualizar la entrada
   existente y marcarla como completada solo después de verificar el resultado.

Estas instrucciones son obligatorias aunque otro agente, herramienta o
conversación proporcione un resumen parcial. Los ficheros puntero de arranque en
la raíz del repositorio (uno por cada CLI o agente: `CLAUDE.md`, `AGENTS.md`,
`GEMINI.md`) solo actúan como arranque: **las reglas no se duplican allí ni en
archivos equivalentes**. Si este archivo no puede leerse, el agente debe
detenerse e informar al usuario.

## Convenciones de la bóveda

- Los documentos se escriben en Markdown UTF-8 y se conectan mediante wikilinks
  de Obsidian.
- Las fechas de propiedades usan el formato ISO `AAAA-MM-DD` (o
  `AAAA-MM-DDTHH:mm:ss+ZZ:ZZ` cuando la nota lo requiera).
- Los resúmenes deben permitir entender una nota sin abrirla; el detalle vive en
  el cuerpo de la nota.
- No se deben crear copias paralelas de una regla o decisión. Se enlaza a su
  fuente de verdad.
- Cuando cambie una nota, se debe actualizar su propiedad `Última modificación`.
- Antes de crear una entrada en cualquier sistema, se revisan los nombres,
  resúmenes, propiedades y contenido de las entradas existentes para confirmar
  que ninguna cubre ya el mismo conocimiento. Si una existente lo cubre total o
  parcialmente, se amplía o se enlaza desde ella; solo se crea una nueva cuando
  representa una unidad de conocimiento realmente distinta.

## Sistemas disponibles

### Tareas

_Versión del sistema: 3._

Gestión de trabajos como notas con estado en `Tareas/`, indexadas por
`Tareas/Tareas.base`.

- `Estado` ∈ `Planificando` · `Pendiente` · `En curso` · `Por probar` ·
  `Esperando` · `Hecha` · `Archivada`.
  - `En curso`: alguien trabaja en ella ahora. No se deja en `En curso` una
    tarea que solo espera algo.
  - `Por probar`: el agente terminó y verificó todo lo que podía (tests,
    emulador, contenedores) y el cambio está en `main` (y por tanto en la
    canary); solo falta la prueba del usuario. Se rellenan `Prueba` y la
    sección «Cómo probarlo» (ver abajo). Si la prueba sale bien → `Hecha`; si
    falla → `En curso`, anotando en «Verificación» qué falló.
  - `Esperando`: no avanza por algo ajeno al agente que no es una prueba: un
    tercero (una revisión de winget, el registro de AUR) o una acción del
    usuario (revocar un token, una medición con su uso real). Se rellena
    `Esperando a` con qué o quién y qué hará que se reanude.
  - Una tarea paraguas sigue a sus subtareas: está `Por probar` o `Esperando`
    cuando todas las que le quedan lo están.
- `Prueba` es una línea con lo que el usuario tiene que comprobar, legible
  sin abrir la nota (p. ej. `'Mover el ratón y escribir en un escritorio X11
  real desde Android'`); `Esperando a` es una línea con qué se espera. Las dos
  se dejan vacías (`''`) fuera de su estado. `Estado desde` es la fecha
  (`AAAA-MM-DD`) en que la tarea entró en su estado actual; se actualiza en
  cada cambio de `Estado`, y con ella las vistas `Por probar` y `Esperando`
  ponen arriba lo que lleva más tiempo esperando.
- **Cómo probarlo.** Al pasar a `Por probar`, el cuerpo de la nota lleva una
  sección `## Cómo probarlo` que el usuario pueda seguir semanas después sin
  más contexto: **dónde** (canary desde el commit `abc1234`, versión estable
  mínima, emulador, qué tipo de destino), **preparación** (contenedor, VPN,
  sesión que abrir), **pasos** numerados, **qué debe pasar** y **qué enviar si
  falla** (log, captura, versión). Sin datos del entorno personal (regla 5):
  marcadores como `<destino-windows>`.
- `Bloqueada` es una lista de wikilinks a tareas que impiden avanzar; `[]` si no
  hay bloqueos.
- `Versión` es la versión publicada que lleva el resultado de la tarea a los
  usuarios (`'1.0.1'`, sin `v`). Se deja vacía (`''`) al crear la tarea y la
  rellena el agente al publicar esa versión (regla 4), en las tareas cuyos
  cambios entran en ella. Las tareas que no llegan a ninguna versión (solo
  documentación, infraestructura o decisiones) la dejan vacía. Las tareas
  cerradas antes de la 1.0.0 no se rellenan hacia atrás.
- Al empezar → `En curso`; cuando solo falte la prueba del usuario →
  `Por probar`; al completar y **verificar** (incluida esa prueba) → `Hecha`;
  lo que ya no deba aparecer en el trabajo habitual → `Archivada`.
- Antes de crear una tarea, buscar en TODAS (incluidas `Hecha` y `Archivada`)
  para no duplicar.
- Una tarea nueva es solo su nota en `Tareas/` (desde `Plantillas/Tarea.md`) y
  se sigue en `Tareas/Tareas.base`. No se añade a ninguna nota de hoja de ruta:
  [[Seguimiento de tareas pendientes]] es una lista cerrada con las tareas que
  ya contiene, nacida para el despliegue, y no recoge tareas nuevas.

### ADR (decisiones de arquitectura)

_Versión del sistema: 1._

Decisiones técnicas duraderas como notas con estado en `Decisiones/`, indexadas
por `Decisiones/Decisiones.base`.

- `Estado` ∈ `Propuesta` · `Aceptada` · `Rechazada` · `Obsoleta` · `Reemplazada`.
- Una ADR **no se edita para cambiar la decisión**: se marca `Reemplazada` y se
  crea una nueva que la sustituye, enlazando ambas con `Reemplaza` /
  `Reemplazada por`.
- `Número` es correlativo y no se reutiliza.
- Si una decisión afecta a una tarea, se enlazan mutuamente con wikilinks.

### Decisiones visuales y de estilos

_Versión del sistema: 1._

Acuerdos sobre lenguaje visual, tokens y componentes, en `Decisiones visuales/`,
indexadas por `Decisiones visuales/Decisiones visuales.base`. Sigue el mismo
patrón que ADR.

- `Estado` ∈ `Propuesta` · `Aceptada` · `Reemplazada`.
- `Ámbito` ∈ `Tokens` · `Componente` · `Layout` (amplía el vocabulario si el
  proyecto lo necesita).
- No se edita una decisión visual aceptada para cambiar su contenido: se marca
  `Reemplazada` y se crea una nueva, enlazando ambas.

### Catálogo técnico

_Versión del sistema: 1._

Superficie pública reutilizable (UI, servicios, contratos) en
`Catálogo técnico/`, indexada por `Catálogo técnico/Catálogo técnico.base` y con
nota-índice en `Catálogo técnico.md`.

- `Estado` ∈ `Vigente` · `En revisión` · `Obsoleta`. Se evita depender de piezas
  `En revisión` salvo que el trabajo incluya estabilizarlas.
- Se añade o actualiza la entrada **en el mismo cambio** que crea o altera una
  superficie reutilizable.
- `Tipo`, `Área`, `Feature` y `Ámbito` son vocabularios propios del proyecto:
  **ya acordados** (se fijaron al registrar la primera superficie, `SecretStore`);
  la lista vigente vive en la nota-índice `Catálogo técnico.md`. No inventar
  valores fuera de ese set; se amplía solo cuando aparezca un caso que ninguno
  cubra, actualizando la nota-índice en el mismo cambio.
- Cada nota es un puntero corto: la **implementación** sigue siendo la fuente
  de verdad técnica del contrato.

### Auditorías de seguridad

_Versión del sistema: 2._

Revisiones de seguridad manuales, una nota por auditoría en
`Auditorías de seguridad/`, indexadas por
`Auditorías de seguridad/Auditorías de seguridad.base`, y una nota opaca por
hallazgo sensible en `Auditorías de seguridad/Hallazgos privados/`, indexadas
por `Auditorías de seguridad/Hallazgos privados.base`. La nota-índice
`Auditorías de seguridad.md` es la fuente de verdad del procedimiento: niveles,
herramientas, checklist y cómo trabajar con los avisos privados.

- **Solo a demanda**: una auditoría se hace cuando el usuario la pide en una
  sesión. No hay calendario ni plazos, y ningún agente la lanza ni la exige
  por su cuenta. Un agente puede sugerirla (p. ej. antes de una release con
  cambios de seguridad) y espera la respuesta.
- `Tipo` es la profundidad: `Rápida` (nivel A) · `Estándar` (A y B) ·
  `Completa` (A, B y C).
- `Estado` ∈ `En curso` · `Cerrada`. Una auditoría se cierra cuando **cada
  hallazgo tiene destino** (aviso privado con su `GHSA-…`, o tarea), no cuando
  están todos corregidos.
- **Hallazgos sensibles**: solo como referencia opaca `SEC-AAAA-NN`; su
  contenido vive en el aviso privado (regla 5, "Vulnerabilidades y hallazgos
  de seguridad"). Cada uno tiene una nota `SEC-AAAA-NN` en
  `Hallazgos privados/` con solo severidad, estado, el `GHSA-…`, la auditoría
  que lo encontró y la versión que lo corrige; nunca un título ni una
  descripción. `Estado` ∈ `Abierto` · `Corregido` · `Publicado` ·
  `Descartado`. **Hallazgos no sensibles**: una tarea enlazada desde
  `Hallazgos públicos`.
- Una auditoría cerrada no se reescribe: el seguimiento de cada hallazgo vive
  en su nota de `Hallazgos privados/` (estado) y en su aviso (detalle), o en su
  tarea. La tabla de hallazgos de una auditoría cerrada es la foto del cierre.
- Antes de crear una auditoría se revisa si hay otra `En curso` y se continúa
  esa.

## Reglas fundamentales del proyecto

### 1. Idioma de código y documentación

- **Código en inglés**: identificadores, comentarios y mensajes de código en
  inglés.
- **Documentación en español**: esta bóveda y la documentación del proyecto en
  español.

### 2. Versionado

- El proyecto se versiona con **git y repositorio remoto**: el repositorio
  público `danielperezmartinez/titan-ssh` en GitHub, con rama principal `main`.

### 3. Framework/stack y gestor de paquetes

- **Kotlin Multiplatform (KMP) + Compose Multiplatform**: un único código base
  para Android y escritorio (JVM en Windows y Linux). Ver
  [[Decisiones/ADR-0002 Stack KMP y alcance multiplataforma]].
- **Build y gestor de dependencias: Gradle** (con version catalogs), el estándar
  de KMP. La lógica compartida vive en `commonMain`; lo específico de cada
  plataforma en sus source sets, resolviendo el acceso nativo con `expect`/`actual`.

### 4. Despliegue y distribución

- **Coste cero**: solo canales y servicios gratuitos. Licencia
  **GPL-3.0-or-later**; ID de la app `io.github.danielperezmartinez.*`.
- **Fuente única de artefactos**: GitHub Releases generados por GitHub Actions
  a partir de un tag `vX.Y.Z`.
- **Versión única**: la versión de Android, escritorio y agente sale de un solo
  sitio, el `build.gradle.kts` raíz, y **nunca se escribe a mano en otro
  fichero**. Para subir versión se crea y se sube el tag `vX.Y.Z` en `main`; el
  build lo lee (en CI con `-PtitanVersion`, en local también del tag que apunte
  a HEAD). Sin tag, la versión es `0.0.0-dev`. `./gradlew printVersion` muestra
  los valores derivados. Formato admitido, fórmula del `versionCode` y límites
  en [[Versionado único desde tag de git]].
- **Fase de producto (desde `1.0.0`)**: solo se publican versiones estables,
  que llegan solas a todas las instalaciones con el actualizador de la app
  ([[ADR-0025 Actualización automática de la app]]), que solo instala
  versiones estables. Se sigue SemVer:
  - `X.Y.Z+1` para arreglos sin cambios visibles de funcionamiento.
  - `X.Y+1.0` para funciones nuevas o cambios de comportamiento compatibles.
  - `X+1.0.0` solo para una ruptura: una configuración que no se puede migrar
    sola o un agente incompatible con las apps anteriores.
  - **No se publican betas** (`-alpha.N`, `-beta.N`, `-rc.N`), aunque el build
    las sigue aceptando. Lo que necesite pruebas se prueba durante el
    desarrollo, antes de cerrar su tarea (ver **Prueba en Android**), y en la
    canary.
- **Canary** ([[ADR-0027 Canal canary instalable junto a la app estable]]):
  otra app (`titan-ssh canary`, icono con el cursor naranja) que se instala
  junto a la estable en los dispositivos del usuario. CI la construye en cada
  push a `main` y la publica en el Release rodante `canary`, del que se
  actualiza sola. Tiene sus propias carpetas, secretos y agente en los
  destinos, y copia la configuración de la estable en un solo sentido. No es
  una versión: no lleva tag `vX.Y.Z`, no necesita permiso del usuario y no
  sustituye a la prueba de cada tarea. Antes de publicar una versión, el
  usuario puede probar en la canary el commit al que se pondrá el tag.
  Localmente se construye con `-PtitanChannel=canary`.
- **Prueba en Android** (al cerrar cada tarea, no al publicar). Los cambios que
  afectan a Android se prueban en el emulador (`Pixel_9_Pro_XL`) con una build
  de debug, no con un Release ni lanzando el workflow a mano. El agente enseña
  al usuario capturas del emulador (ficheros o carpeta) y deja el emulador
  abierto con la app instalada y un destino de pruebas preparado, para que el
  usuario la pruebe él mismo. El destino es el servidor SSH de pruebas de
  `tools/test-sshd/` (un contenedor que se conserva y solo se enciende y se
  apaga). Con eso la verificación "en dispositivo" de la tarea queda cubierta.
  Varias sesiones comparten el emulador: cada worktree instala su propia build
  de debug (sufijo de paquete automático) y **nadie maneja el emulador sin
  tener el turno** de `tools/emulator-lock/` (ver su `README.md`), que se
  suelta al terminar las capturas. Si está ocupado, se avanza con lo que no
  necesite el emulador y, cuando no quede nada, se espera el turno de la forma
  que permita el agente (en segundo plano si puede).
- **Publicar una versión** (workflow `.github/workflows/release.yml`; detalle
  en [[Pipeline de release en GitHub Actions]]). Un agente **solo publica
  cuando el usuario lo pide en esa sesión**: el tag y el Release son públicos
  y no se pueden retirar. Si al agente le parece buen momento, lo propone y
  espera la respuesta. Las pruebas no se repiten al publicar: se hicieron al
  cerrar cada tarea, y el workflow vuelve a pasar los tests, comprueba la
  firma de la APK y firma y verifica `titan-update.json`, y si algo falla no
  publica nada.
  1. **Número de versión.** El agente lee `git log v<última>..main` y las
     tareas que han pasado a `Hecha` desde la última versión, clasifica cada
     cambio según **Fase de producto** (arreglo → `X.Y.Z+1`; función nueva o
     cambio de comportamiento → `X.Y+1.0`; ruptura → `X+1.0.0`) y **propone al
     usuario** el número con la lista de cambios que lo justifica. Se publica
     el que confirme el usuario: sin sufijo y nunca un tag ya usado. Se
     comprueba con `./gradlew printVersion -PtitanVersion=<versión>`.
  2. `main` con todo lo que entra en la versión subido. Se revisa el diff
     desde la última versión según la regla 5.
  3. `git tag -a v<versión> -m "titan-ssh <versión>"` y
     `git push origin v<versión>`.
  4. Se sigue el run del workflow `Release` hasta que acaben todos sus jobs.
     **Si falla, el agente se para**: explica al usuario qué ha fallado (con
     el job y el error) y qué propone para arreglarlo, y no cambia nada hasta
     que el usuario lo confirme. Un tag publicado no se mueve ni se borra sin
     su permiso; lo normal es arreglarlo en `main` y publicar el número
     siguiente. Los jobs `aur` y `winget` van después del Release: si solo
     falla uno de ellos, el Release vale y basta con reintentar ese job. Solo
     se ejecutan si la variable del repositorio `AUR_ENABLED` o
     `WINGET_ENABLED` vale `true`.
  5. Se confirma que el Release existe con sus assets y no es pre-release. Las
     comprobaciones de firma y de SHA-256 ya las hace el workflow.
  6. Se rellena `Versión` en las tareas cuyos cambios entran en esta versión
     (sistema de Tareas) y se suben esas notas.
  - La llegada a los dispositivos no se sigue paso a paso: la versión llega
    sola con el actualizador. Si en algún dispositivo no llega, el usuario lo
    avisa y se mira el `update.log` del actualizador (en la caché de la app en
    escritorio y en `no_backup/update/` en Android) antes de instalar a mano.
  - Para probar el pipeline sin publicar nada, se lanza el workflow a mano
    (`workflow_dispatch`): genera los paquetes como artefactos del run.
  - Los secretos del keystore (`TITAN_KEYSTORE_BASE64`, `TITAN_KEY_ALIAS`,
    `TITAN_KEYSTORE_PASSWORD`) ya están en GitHub y los gestiona el usuario,
    igual que `AUR_SSH_PRIVATE_KEY` y `WINGET_TOKEN` (ver
    [[Canal Arch Linux AUR]] y [[Canal Windows winget]]), y que
    `TITAN_UPDATE_SIGNING_KEY`, la clave que firma el manifiesto de
    actualizaciones. Sin ella el job `publish` falla. Su clave pública está en
    `shared/update-keys.txt`.
- **Windows**: MSI (Compose/jpackage) y winget; firma con SignPath Foundation
  cuando se conceda.
- **Linux**: Flatpak en Flathub (principal), AUR para Arch, y `.deb`/`.rpm`/`tar.gz`.
- **Android**: APK firmada en Releases (descarga directa u Obtainium) e IzzyOnDroid; Google Play
  aplazado (cuota de 25 $), con AAB y clave de subida preparados.
- Detalle y alternativas descartadas en
  [[Decisiones/ADR-0011 Distribución y canales de publicación]].
- El código se versiona con git y repositorio remoto (ver regla 2).

### 5. Repositorio público: nada sensible ni personal

El repositorio es **público**. Todo lo que se sube a GitHub (código, esta
bóveda `docs/`, mensajes de commit, nombres de rama, workflows, logs de CI y
artefactos) queda a la vista de cualquiera y **no se puede retirar**: borrarlo
después no lo quita del historial, de los forks ni de las cachés.

**Nunca se sube:**

- Secretos: claves privadas, contraseñas, tokens, keystores, claves de firma,
  `local.properties` o ficheros `.env`.
- Datos del entorno personal del usuario: nombres de host o de máquina,
  direcciones IP (de LAN, de Tailscale o públicas), nombres de la tailnet o de
  dominios propios, nombres de dispositivos, usuarios del sistema operativo,
  rutas personales (`C:\Users\<nombre>`, `/home/<nombre>`, unidades y carpetas
  propias), huellas de claves de host, correos personales ni la topología de su
  red.
- Vulnerabilidades sin corregir: van solo a avisos privados de GitHub (ver
  "Vulnerabilidades y hallazgos de seguridad" más abajo).

**Cómo se hace en su lugar:**

- En la documentación se usan marcadores genéricos: `<host-de-pruebas>`,
  `<usuario>`, `<clave-de-pruebas>`, `<ruta-a-un-jdk-completo>`, "el Pixel de
  pruebas". Los valores reales viven **fuera del repositorio** (la memoria local
  del agente, que no se versiona).
- Los tests contra entornos reales reciben los datos por propiedades `-P` o
  variables de entorno en tiempo de ejecución, y se saltan si faltan. Nunca van
  escritos en el código.
- CI no depende de máquinas, redes ni cuentas personales del usuario (nada de
  runners propios ni de su tailnet). Los secretos que CI necesite de verdad
  (p. ej. el keystore de firma) van solo en GitHub Secrets y nunca se imprimen
  en los logs.
- **Antes de cada commit y de cada push** se revisa el diff buscando estos
  datos, incluidos los mensajes de commit. Una comprobación rápida:
  `git diff --cached | grep -niE 'BEGIN .*PRIVATE KEY|password|token|[0-9]{1,3}(\.[0-9]{1,3}){3}|Users[/\\]|/home/'`,
  más el nombre de las máquinas y del usuario del entorno real.
- Si algo sensible ya se ha subido, se avisa al usuario **de inmediato**. No se
  reescribe el historial ni se fuerza un push sin su permiso explícito. Si era
  un secreto, se da por comprometido y se revoca o se rota.

#### Vulnerabilidades y hallazgos de seguridad

Una vulnerabilidad sin corregir es tan sensible como un secreto: publicarla en
el repositorio es explicar cómo atacar a quien usa la versión publicada.

- **Criterio.** Un hallazgo es **sensible** si describe cómo dañar, espiar o
  engañar a usuarios de una versión ya publicada y todavía no hay una versión
  publicada que lo corrija. Ante la duda, se trata como sensible.
- **Dónde vive.** Cada hallazgo sensible es un **aviso privado de GitHub**
  (*Security Advisory* en borrador, en la pestaña *Security* del repositorio),
  uno por hallazgo: descripción, cómo reproducirlo, impacto, severidad y
  propuesta de arreglo. Es su única fuente de verdad mientras no se publique.
- **Qué nunca lo cuenta** mientras no se publique el arreglo: esta bóveda, los
  mensajes de commit, los nombres de rama, los títulos y cuerpos de PR, las
  issues, los comentarios de código, los nombres de test ni los checklists de
  auditoría. Las comprobaciones de regresión específicas de un hallazgo se
  añaden al checklist cuando su aviso se publica.
- **En la bóveda** solo se anota una referencia opaca: `SEC-AAAA-NN`
  (correlativo por año, no se reutiliza), severidad, estado y el ID `GHSA-…`
  del aviso, en su nota de `Auditorías de seguridad/Hallazgos privados/`.
  Nunca un título descriptivo.
- **El arreglo** se prepara en local, sin subirlo hasta que se suba junto con
  el tag de la versión que lo publica, y con mensajes de commit neutros que no
  expliquen el fallo. El procedimiento detallado está en la nota-índice
  `Auditorías de seguridad.md`, en "Corregir un hallazgo privado".
- **Al publicar el arreglo** se publica el aviso (con CVE si procede). A partir
  de ahí la bóveda ya puede enlazarlo y describirlo, y su nota de hallazgo
  pasa a `Publicado`.
- **Lo no sensible** (endurecimiento general, CVE públicos de dependencias cuyas
  versiones ya están a la vista, piezas de infraestructura que faltan) se
  gestiona con tareas normales.
- **Los agentes** no crean, publican ni cierran avisos por su cuenta: preparan
  el borrador y el usuario lo da de alta, o autoriza a hacerlo en esa sesión.
  Publicar un aviso es irreversible.
- Si un hallazgo sensible llega al repositorio por error, se aplica la misma
  regla que a un secreto: avisar al usuario de inmediato, no reescribir el
  historial sin su permiso y priorizar el arreglo.

## Propósito del proyecto

**titan-ssh** es un cliente SSH **multiplataforma** (objetivo: Linux, Windows y
Android), pensado como alternativa a Termius para conectarse a ordenadores y
servidores propios. La idea nació como app de móvil, pero el alcance objetivo es
usarlo también desde escritorio.

Nace de dos carencias concretas de Termius:

1. **Fragilidad de la sesión ante la red.** Ante la mínima interferencia o
   microcorte, Termius cierra la sesión activa y obliga a reconectar desde cero,
   volver a navegar hasta la ruta de trabajo y reiniciar a mano los procesos que
   estaban corriendo. titan-ssh debe sobrevivir a los microcortes sin perder el
   trabajo del usuario.
2. **Automatización tras un muro de pago.** La ejecución de scripts al iniciar una
   sesión (entrar directo al directorio de un proyecto, arrancar herramientas o
   servicios como Claude, etc.) solo está en Termius Pro. titan-ssh ofrece esa
   automatización por sesión de forma gratuita.

titan-ssh **no busca imitar a Termius** ni en su aspecto visual ni en su modelo
de gestión de configuraciones (aspectos que no convencen al usuario); replica la
funcionalidad clave con un enfoque propio.

Pilares del producto:

- **Gestión de conexiones y automatización:** panel para guardar hosts y
  sesiones, con scripts de inicio (o post-inicio) por sesión.
- **Seguridad y privacidad (prioridad alta):** al ser conexiones SSH, nunca se
  almacenan credenciales en texto plano; se delega en el almacén de secretos
  nativo de cada plataforma (Android Keystore / Credential Manager en Android, y
  el equivalente nativo en Windows y Linux).
- **Terminal multitarea resiliente:** varias conexiones simultáneas en pestañas,
  con conmutación ágil y sesiones que aguantan los microcortes de red.

## Arquitectura objetivo

Arquitectura inicial objetivo derivada del stack (KMP + Compose Multiplatform).
Se refinará a medida que aparezca código; ver
[[Decisiones/ADR-0002 Stack KMP y alcance multiplataforma]].

### Estructura de source sets

- `commonMain`: lógica de dominio y UI compartida (Compose Multiplatform):
  modelo de hosts/sesiones, motor de terminal, orquestación de sesiones y
  reconexión, y las interfaces `expect` de servicios de plataforma (entre ellas
  `SecretStore`).
- `jvmShared` (source set intermedio del que dependen `androidMain` y
  `desktopMain`): código pure-Java común a Android y escritorio, en particular el
  cliente SSH (sshj) y la lógica de reconexión que se apoya en su heartbeat. Ver
  [[Decisiones/ADR-0004 Librería SSH]].
- `androidMain`: `actual` de Android (`SecretStore` sobre Keystore + biometría,
  integración del ciclo de vida) y empaquetado de la app Android.
- `desktopMain` (JVM): `actual` para Windows y Linux (`SecretStore` sobre DPAPI /
  Secret Service, empaquetado de escritorio). Dentro se resuelven las diferencias
  Windows vs Linux del almacén de secretos.

### Reglas de dependencia

- `commonMain` **no** depende de código específico de plataforma: todo acceso
  nativo pasa por interfaces `expect`/`actual`.
- La custodia de credenciales solo se implementa en los source sets de
  plataforma, nunca en `commonMain` (ver
  [[Decisiones/ADR-0001 Credenciales en almacén nativo del SO]]).
- Las adaptaciones específicas de una plataforma no se filtran a la lógica
  compartida.

### Pendiente de decidir

- Librería de escritorio concreta para el `SecretStore` en Windows/Linux
  (candidatas: credential-secure-storage-for-java, java-keyring); se cierra en la
  implementación (ver [[Decisiones/ADR-0001 Credenciales en almacén nativo del SO]]).
- Empaquetado de escritorio concreto (formatos Windows/Linux): **resuelto** en
  [[Decisiones/ADR-0011 Distribución y canales de publicación]].
- Taxonomía del catálogo técnico (Tipo, Área, Feature, Ámbito): **resuelto** —
  acordada al registrar la primera superficie (`SecretStore`); ver la nota-índice
  `Catálogo técnico.md`.
