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

- El proyecto se versiona con **git y repositorio remoto** (p. ej. GitHub). El
  directorio **aún no es un repositorio git**: inicializarlo es un paso pendiente.

### 3. Framework/stack y gestor de paquetes

- **Kotlin Multiplatform (KMP) + Compose Multiplatform**: un único código base
  para Android y escritorio (JVM en Windows y Linux). Ver
  [[Decisiones/ADR-0002 Stack KMP y alcance multiplataforma]].
- **Build y gestor de dependencias: Gradle** (con version catalogs), el estándar
  de KMP. La lógica compartida vive en `commonMain`; lo específico de cada
  plataforma en sus source sets, resolviendo el acceso nativo con `expect`/`actual`.

### 4. Despliegue y distribución

- **Android**: Google Play Store.
- **Escritorio (Windows/Linux)**: empaquetado nativo de Compose Multiplatform
  (p. ej. `.msi`/`.exe` y `.deb`/binario). El detalle concreto queda por afinar.
- El código se versiona con git y repositorio remoto (ver regla 2).

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
- Empaquetado de escritorio concreto (formatos Windows/Linux).
- Taxonomía del catálogo técnico (Tipo, Área, Feature, Ámbito): **resuelto** —
  acordada al registrar la primera superficie (`SecretStore`); ver la nota-índice
  `Catálogo técnico.md`.
