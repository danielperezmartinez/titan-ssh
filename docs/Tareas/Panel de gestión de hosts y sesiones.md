---
Nombre: Panel de gestión de hosts y sesiones
Estado: Hecha
Resumen: 'Área de Configuración entregada y verificada: modelo Host/Sesión/Script/Snippet/Grupo/Túnel (@Serializable), persistencia JSON app-privada (ConfigStore, secretos solo por referencia), controlador con StateFlow y CRUD, y UI Compose dark-first (listas + editores de host, sesión, snippet y grupos, con scripts guiados reordenables, túneles, ProxyJump, nivel de resiliencia, apariencia y duplicar). Shell de dos áreas cableado; Sesiones queda como lanzadera hasta el terminal.'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]]; formaliza [[ADR-0007 Modelo y persistencia de configuración]] y consume [[ADR-0001 Credenciales en almacén nativo del SO]] / [[ADR-0003 Modelo de resiliencia por niveles]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-18T14:37:00+02:00
---

# Panel de gestión de hosts y sesiones

## Objetivo

Permitir al usuario crear, guardar y organizar hosts y sesiones SSH desde un
panel propio. El modelo de gestión de configuraciones no debe replicar el de
Termius (aspecto que no convence al usuario); se busca un enfoque propio.

## Criterios de finalización

- **Hosts**: crear, editar, eliminar y persistir hosts reutilizables (hostname/IP,
  puerto, usuario por defecto, método de auth, host key/known_hosts, keepalive,
  jump host/ProxyJump, alias, color/icono).
- **Sesiones**: crear sesiones que **reutilizan un host** almacenado y añaden lo
  suyo (scripts vía [[Scripts de inicio por sesión]], directorio inicial, nivel de
  resiliencia según [[ADR-0003 Modelo de resiliencia por niveles]], túneles/port
  forwarding, apariencia). Pueden **sobreescribir** valores por defecto del host.
- **Organización**: agrupar hosts y sesiones (carpetas/etiquetas por proyecto).
- La configuración persiste entre arranques; las credenciales se custodian según
  [[ADR-0001 Credenciales en almacén nativo del SO]].

## Elementos del área de Configuración (alcance v1, confirmado)

- **Snippets / comandos rápidos** reutilizables (biblioteca global insertable en
  cualquier sesión), como **tercer elemento de primera clase** junto a hosts y
  sesiones. Son también los scripts "bajo demanda" (ver
  [[Scripts de inicio por sesión]]).
- **Túneles / port forwarding** por sesión (local, remoto, SOCKS dinámico) y
  **jump host (ProxyJump)** en el host.
- **Nivel de resiliencia por sesión** (base / auto-tmux / agente, ver
  [[ADR-0003 Modelo de resiliencia por niveles]]).
- **Agrupación por proyecto** (carpetas/etiquetas) para hosts y sesiones.
- **Apariencia del terminal** (fuente, tamaño, tema de color) global con override
  por sesión.
- **Plantillas / duplicar sesión** para crear una sesión a partir de otra.

## Verificación

- **Build ambos targets OK** (`JAVA_HOME` al JBR, wrapper, `--console=plain`):
  `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin` → `BUILD
  SUCCESSFUL`. APK: `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
- **Tests `:shared:desktopTest`** (I/O real, sin fallos):
  - `ConfigModelTest` (`tests=10`): resolución sesión+host con y sin overrides,
    ProxyJump, host ausente lanza `ConfigResolutionException`, merge de apariencia
    (sesión > host > default), mapeo `HostAuth → AuthMethod` con `byPreference()`,
    `scriptsFor()` filtra deshabilitados por fase, duplicar sesión con ids nuevos
    e independientes, borrar host limpia la referencia ProxyJump, borrar grupo
    desasocia miembros.
  - `JsonFileConfigStoreTest` (`tests=3`): round-trip completo (los tres tipos de
    auth, scripts, túneles, grupos, snippets, apariencia) contra fichero temporal;
    fichero ausente carga config vacía; el documento persistido contiene solo
    **referencias** (no secretos) y etiqueta el `HostAuth` sellado con el
    discriminador `kind`.
  Fuentes: `shared/src/desktopTest/kotlin/im/gar/titanssh/config/`.
- **Escritorio:** `:desktopApp:run` arranca la ventana Compose con el shell de dos
  áreas (pendiente de confirmación visual del usuario, como en tareas previas).
- **Android:** compile-verified + APK; el arranque en dispositivo/emulador queda
  como comprobación del usuario.

## Resultado

Área de Configuración v1 entregada (paquete `im.gar.titanssh.config` + UI en
`im.gar.titanssh.ui`), según [[ADR-0007 Modelo y persistencia de configuración]]:

- **Modelo** `@Serializable` en `commonMain`: `Host` (alias, hostname, puerto,
  usuario, `HostAuth` password/clave-software/clave-hardware, política
  known_hosts, keepalive, ProxyJump por id, apariencia, grupo, tags, marcador),
  `Session` (referencia a host + overrides usuario/puerto, `cd` inicial, nivel de
  resiliencia, scripts, túneles, apariencia, grupo, tags), `SessionScript`
  (fase, cuerpo/`snippetId`, `ScriptBehavior`, `ReconnectBehavior`, envVars,
  secretRefs), `Snippet`, `Group`, `Tunnel`, `TerminalAppearance`, raíz
  `TitanConfig`. Resolución `resolve(session)` → `SshEndpoint` + `HostAuth`.
- **Persistencia** `ConfigStore` (JSON app-privado, escritura atómica) en
  `jvmShared` (`JsonFileConfigStore`), directorio `expect`/`actual` por
  plataforma. **Secretos solo por referencia** (SecretStore) o alias hardware.
- **Estado**: `ConfigController` (StateFlow + CRUD + `duplicateSession`).
- **UI Compose dark-first** (tokens opencode: mono, marcadores ASCII, hairlines,
  4px/0px): componentes reutilizables (`TitanTextField`, `TitanButton`,
  `TitanCheck`, `TitanSegmented`, `TitanDropdown`, `ListRow`, `EditorScaffold`…),
  shell de dos áreas (`AppShell`), área de Configuración con pestañas
  Hosts/Sesiones/Snippets/Grupos, y editores completos de host, sesión (con
  formulario guiado de scripts reordenables, túneles, ProxyJump, resiliencia,
  apariencia, duplicar), snippet y grupos.
- **Cableado**: `App()` → `AppShell()`; `MainActivity` inicializa
  `AndroidConfigContext` y muestra `App()` (la pantalla de prueba del signer se
  conserva en el árbol como banco de pruebas, ya no es el entrypoint); escritorio
  ya mostraba `App()`.

Cierra también [[Scripts de inicio por sesión]] (el formulario guiado y los
snippets viven dentro del editor de sesión). El terminal multipestaña real sigue
en [[Terminal multipestaña con sesiones simultáneas]]; aquí el área de Sesiones
es una lanzadera que ya demuestra la resolución config → motor.

Nota catálogo técnico: las nuevas superficies reutilizables **ya están
catalogadas** (la taxonomía estaba acordada desde `SecretStore`; el "por definir"
era stale y se ha corregido): [[Modelo de configuración]], [[ConfigStore]],
[[ConfigController]], [[Tema y tokens visuales]] y [[Componentes UI compartidos]].

## Repaso de diseño (2026-09-18)

Tras revisar la UI contra las directrices visuales se corrigieron desviaciones y
se cerró el pendiente de tipografía, dejando la app conforme a
[[Vocabulario ASCII ampliado y disciplina de color]] (que amplía
[[Tokens visuales dark-first base opencode]]):

- **Disciplina de color**: la rampa semántica (success/warning/danger) deja de
  usarse como adorno; las filas de configuración van en color neutro y el verde/
  ámbar/rojo quedan reservados a estado real. `accent` solo para interacción/
  selección. Botón primario = superficie elevada + hairline accent (sin relleno
  semántico).
- **Marcadores ASCII**: se sustituye el `[✓]` no-ASCII por `[ok]`; se formaliza el
  set ampliado (`[^]` `[v]` `[#]` `[<]` `[ok]`).
- **Tipografía**: se **bundlea JetBrains Mono** (pesos 400/500/700, OFL-1.1 en
  `third_party/JetBrainsMono/`) vía recursos de Compose, en vez del monospace del
  sistema. Cierra el pendiente heredado de [[Inicializar repositorio y esqueleto KMP]].

Reverificado: `:shared:compileKotlinDesktop` + `:shared:compileAndroidMain` +
`:desktopApp:compileKotlin` + `:androidApp:compileDebugKotlin` → `BUILD
SUCCESSFUL`; `:shared:desktopTest` sin fallos.
