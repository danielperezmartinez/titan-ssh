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

_Versión del sistema: 1._

Gestión de trabajos como notas con estado en `Tareas/`, indexadas por
`Tareas/Tareas.base`.

- `Estado` ∈ `Planificando` · `Pendiente` · `En curso` · `Hecha` · `Archivada`.
- `Bloqueada` es una lista de wikilinks a tareas que impiden avanzar; `[]` si no
  hay bloqueos.
- Al empezar → `En curso`; al completar y **verificar** → `Hecha`; lo que ya no
  deba aparecer en el trabajo habitual → `Archivada`.
- Antes de crear una tarea, buscar en TODAS (incluidas `Hecha` y `Archivada`)
  para no duplicar.

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
  fichero**. Para subir versión se crea y se sube el tag `vX.Y.Z` (o
  `vX.Y.Z-beta.N`, `-alpha.N`, `-rc.N`) en `main`; el build lo lee (en CI con
  `-PtitanVersion`, en local también del tag que apunte a HEAD). Sin tag, la
  versión es `0.0.0-dev`. `./gradlew printVersion` muestra los valores
  derivados. Formato admitido, fórmula del `versionCode` y límites en
  [[Versionado único desde tag de git]].
- **Publicar una versión** (workflow `.github/workflows/release.yml`; detalle
  en [[Pipeline de release en GitHub Actions]]). Un agente **solo publica
  cuando el usuario lo pide en esa sesión**: el tag y el Release son públicos
  y no se pueden retirar. Si al agente le parece buen momento, lo propone y
  espera la respuesta.
  1. `main` con todo subido y el árbol limpio. Se revisa el diff según la
     regla 5.
  2. Versión: el siguiente `-beta.N` para el canal interno, o sin sufijo para
     una estable. Nunca se reutiliza un tag. Se comprueba con
     `./gradlew printVersion -PtitanVersion=<versión>`.
  3. `git tag -a v<versión> -m "titan-ssh <versión>"` y
     `git push origin v<versión>`.
  4. Se sigue el run del workflow `Release` hasta que acaben sus seis jobs. Si
     falla, se arregla en `main` y se publica el número siguiente: un tag
     publicado no se mueve ni se borra sin permiso del usuario.
  5. Se comprueba el Release: marcado como pre-release si la versión lleva
     sufijo, `sha256sum -c SHA256SUMS` sin errores y la APK firmada con la
     clave de release (su huella está en el `README.md` de la raíz; CI también
     la comprueba).
  6. El usuario instala la APK en el Pixel descargándola del Release (no usa
     Obtainium) y confirma que funciona. Se anota en la tarea correspondiente.
  - Para probar el pipeline sin publicar nada, se lanza el workflow a mano
    (`workflow_dispatch`): genera los paquetes como artefactos del run.
  - Los secretos del keystore (`TITAN_KEYSTORE_BASE64`, `TITAN_KEY_ALIAS`,
    `TITAN_KEYSTORE_PASSWORD`) ya están en GitHub y los gestiona el usuario.
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
