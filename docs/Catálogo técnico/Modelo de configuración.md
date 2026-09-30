---
Nombre: "Modelo de configuración"
Tipo: "Modelo de dominio"
Área: "Configuración"
Feature: "Gestión de hosts"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/Model.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.config"
Resumen: "Modelo de dominio @Serializable del área Configuración. Host (dónde/cómo conectar, reutilizable) y Session (qué hacer al conectar, referencia a host con overrides), más SessionScript, LibraryScript (la biblioteca de scripts, que sustituyó a los snippets), Group (carpeta anidable por parentId, con su estado plegado), Tunnel, TerminalAppearance y la raíz TitanConfig (versión 3, migrada desde las anteriores por ConfigMigration), con listas de grupos independientes para hosts (hostGroups) y sesiones (sessionGroups) según GroupScope. Nunca contiene material secreto: contraseñas/passphrases/claves software van por SecretRef y la clave hardware por alias del almacén del SO. resolve(session) fusiona overrides y devuelve un ResolvedConnection (SshEndpoint + auth + apariencia + ProxyJump); toAuthMethod() mapea al AuthMethod runtime con la preferencia de ADR-0005. Un SessionScript puede ser propio o una referencia a un LibraryScript (libraryScriptId): resolve() entrega la sesión con effectiveScripts(), que rellena cada referencia con el contenido actual de la biblioteca; onDemandScripts() da el menú de scripts de una pestaña."
Última modificación: 2026-10-01T00:50:00+02:00
---

# Modelo de configuración

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[Model.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/Model.kt)
(los tipos `@Serializable`) y
[Resolution.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/Resolution.kt)
(`resolve`, `effectiveScripts`, `onDemandScripts`, `sessionsUsing`, `mergedWith`, `toAuthMethod`, `scriptsFor`).

Piezas y detalles relacionados:

- `Host` / `Session` — host reutilizable + sesión que lo referencia y puede
  sobreescribir `username`/`port`; la sesión añade `cd` inicial, nivel de
  resiliencia (`ResilienceLevel`, ADR-0003), scripts, túneles y apariencia.
- `SessionScript` / `ScriptPhase` / `ScriptBehavior` / `ReconnectBehavior` —
  modelo de la automatización de [[Scripts de inicio por sesión]]; la ejecución
  vive en [[ScriptRunner]], no aquí.
- `LibraryScript` — script de la biblioteca (pestaña Scripts): comando,
  comportamiento, variables y secretos. Un `SessionScript` con
  `libraryScriptId` lo referencia y solo aporta fase, orden, activado y
  comportamiento al reconectar
  ([[ADR-0013 Biblioteca de scripts unificada con los snippets]]).
- `Group` / `GroupScope` — carpetas de la lista de hosts (`hostGroups`) o de
  la de sesiones (`sessionGroups`), independientes; `parentId` las anida dentro
  de su misma lista y `collapsed` recuerda si quedaron plegadas
  ([[ADR-0015 Grupos de hosts y de sesiones independientes y anidados]]). La
  estructura de carpetas la calcula [[GroupTree]].
- `ConfigMigration` — sube el JSON de versiones anteriores a la actual antes de
  decodificarlo; la de la 1 a la 2 nunca cambia lo que se ejecuta, y la de la 2
  a la 3 reparte los grupos compartidos sin cambiar el grupo de nadie.
- `HostAuth` (Password / SoftwareKey / HardwareKey) — solo referencias; se
  materializa a credenciales vía [[SecretStore]] en tiempo de conexión.
- `ResolvedConnection` — lo que consume el motor: produce el `SshEndpoint` del
  contrato [[SshConnector]].
- Ids opacos en
  [Ids.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/Ids.kt).

Formalizado en [[ADR-0007 Modelo y persistencia de configuración]]; consume
[[ADR-0001 Credenciales en almacén nativo del SO]] y
[[ADR-0005 Autenticación SSH y verificación de host]]. Se persiste con
[[ConfigStore]] y se gobierna en memoria con [[ConfigController]].
