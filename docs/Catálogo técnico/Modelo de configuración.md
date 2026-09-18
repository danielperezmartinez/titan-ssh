---
Nombre: "Modelo de configuración"
Tipo: "Modelo de dominio"
Área: "Configuración"
Feature: "Gestión de hosts"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/config/Model.kt"
Entrada pública: "im.gar.titanssh.config"
Resumen: "Modelo de dominio @Serializable del área Configuración. Host (dónde/cómo conectar, reutilizable) y Session (qué hacer al conectar, referencia a host con overrides), más SessionScript, Snippet, Group, Tunnel, TerminalAppearance y la raíz TitanConfig (versionada). Nunca contiene material secreto: contraseñas/passphrases/claves software van por SecretRef y la clave hardware por alias del almacén del SO. resolve(session) fusiona overrides y devuelve un ResolvedConnection (SshEndpoint + auth + apariencia + ProxyJump); toAuthMethod() mapea al AuthMethod runtime con la preferencia de ADR-0005."
Última modificación: 2026-09-18T15:30:00+02:00
---

# Modelo de configuración

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[Model.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/config/Model.kt)
(los tipos `@Serializable`) y
[Resolution.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/config/Resolution.kt)
(`resolve`, `mergedWith`, `toAuthMethod`, `scriptsFor`).

Piezas y detalles relacionados:

- `Host` / `Session` — host reutilizable + sesión que lo referencia y puede
  sobreescribir `username`/`port`; la sesión añade `cd` inicial, nivel de
  resiliencia (`ResilienceLevel`, ADR-0003), scripts, túneles y apariencia.
- `SessionScript` / `ScriptPhase` / `ScriptBehavior` / `ReconnectBehavior` —
  modelo de la automatización de [[Scripts de inicio por sesión]]; la ejecución
  (aún pendiente) vive en el flujo de lanzamiento del terminal, no aquí.
- `HostAuth` (Password / SoftwareKey / HardwareKey) — solo referencias; se
  materializa a credenciales vía [[SecretStore]] en tiempo de conexión.
- `ResolvedConnection` — lo que consume el motor: produce el `SshEndpoint` del
  contrato [[SshConnector]].
- Ids opacos en
  [Ids.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/config/Ids.kt).

Formalizado en [[ADR-0007 Modelo y persistencia de configuración]]; consume
[[ADR-0001 Credenciales en almacén nativo del SO]] y
[[ADR-0005 Autenticación SSH y verificación de host]]. Se persiste con
[[ConfigStore]] y se gobierna en memoria con [[ConfigController]].
