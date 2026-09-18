---
Nombre: Modelo y persistencia de configuración (hosts, sesiones, scripts)
Número: 7
Estado: Aceptada
Resumen: 'La configuración del área de Configuración (hosts reutilizables, sesiones que los referencian, scripts de inicio, snippets, grupos y apariencia) se modela como tipos @Serializable en commonMain y se persiste como un único documento JSON en un fichero app-privado, con escritura atómica. Los secretos NUNCA se guardan aquí: solo referencias al SecretStore (o el alias de la clave hardware). La persistencia se comparte en jvmShared; solo el directorio base es expect/actual.'
Decisión: Modelar la config con kotlinx.serialization y persistirla en un JSON app-privado (ConfigStore), guardando únicamente referencias a secretos; resolver sesión+host a los tipos del motor (SshEndpoint/AuthMethod) en commonMain.
Consecuencias: Un formato portable y legible entre plataformas; la seguridad se mantiene delegada en el SecretStore (ADR-0001); el modelo cubre campos avanzados (túneles, ProxyJump, resiliencia, apariencia) aunque su ejecución la cableen tareas posteriores.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-18T14:05:00+02:00
Última modificación: 2026-09-18T14:05:00+02:00
---

# ADR-0007 · Modelo y persistencia de configuración (hosts, sesiones, scripts)

## Contexto

El área de Configuración ([[Arquitectura de dos áreas Configuración y Sesiones]])
necesita **modelar y persistir** hosts reutilizables, sesiones que los
referencian, scripts de inicio ([[Scripts de inicio por sesión]]), la biblioteca
de snippets, grupos y la apariencia del terminal. Hasta ahora el proyecto tenía
dominio SSH y `SecretStore` pero **ninguna capa de configuración persistente**.

Restricciones del proyecto que condicionan la decisión:

- Seguridad prioritaria: **nunca** credenciales en texto plano; se delega en el
  almacén nativo (ADR-0001 / ADR-0005).
- KMP con `commonMain` libre de dependencias de plataforma; ambos targets son
  JVM, con un source set intermedio `jvmShared` (ADR-0002 / ADR-0006).

## Decisión

1. **Modelo en `commonMain`** (`im.gar.titanssh.config`): tipos `@Serializable`
   (`Host`, `Session`, `SessionScript`, `Snippet`, `Group`, `Tunnel`,
   `TerminalAppearance`, `HostAuth`, enums) bajo una raíz `TitanConfig` con
   `version` para futuras migraciones.
2. **Persistencia con kotlinx.serialization → JSON** en un **único documento**
   (`config.json`) dentro de un directorio app-privado, con **escritura atómica**
   (temp + rename), igual patrón que el `SecretStore`. La implementación
   (`JsonFileConfigStore`) vive en `jvmShared`; solo el directorio base es
   `expect`/`actual` (`createConfigStore()`): Android `filesDir`, escritorio la
   carpeta de config del SO (`%APPDATA%` / `$XDG_CONFIG_HOME`).
3. **Secretos por referencia, nunca por valor.** `HostAuth` guarda el *nombre* de
   la referencia en el `SecretStore` (contraseña, clave software, passphrase) o el
   *alias* de la clave hardware no exportable. El documento de config no contiene
   material secreto.
4. **Resolución config → motor en `commonMain`.** `TitanConfig.resolve(session)`
   aplica los overrides de la sesión sobre los defaults del host y produce el
   `SshEndpoint` y (por referencia) el `HostAuth`, mapeable al `AuthMethod` del
   dominio (que ya codifica la preferencia de ADR-0005). Materializar las
   `SshCredentials` desde el `SecretStore` es trabajo de conexión (tarea del
   terminal), no de la config.
5. **Estado observable** con un `ConfigController` (clase multiplataforma con
   `StateFlow`, sin `ViewModel` de Android) que carga una vez del `ConfigStore`,
   expone la config y persiste cada edición.

## Alternativas consideradas

- **DataStore / SharedPreferences (Android) + otra cosa en escritorio** — obliga
  a dos implementaciones divergentes y no encaja con `jvmShared`; se descarta.
- **SQLite / SQLDelight** — potente pero desproporcionado para la config de un
  único usuario local; añade esquema y migraciones sin necesidad hoy. Se puede
  reconsiderar si la config crece mucho.
- **Guardar los secretos junto a la config (cifrando el fichero)** — rechazado:
  duplica la responsabilidad del `SecretStore` y roza el texto plano. La frontera
  "config = referencias, secretos = SecretStore" es más simple y más segura.

## Consecuencias

- Positivas: un formato portable, legible y versionable; `commonMain` libre de
  plataforma; la seguridad sigue centralizada en el `SecretStore`; el modelo ya
  cubre campos avanzados (túneles, ProxyJump, nivel de resiliencia, apariencia,
  plantillas) aunque su *ejecución* la cableen las tareas de terminal y
  resiliencia.
- Negativas / compromisos: reescribe el documento completo en cada guardado (coste
  irrelevante a esta escala); no hay migración automática todavía (solo el campo
  `version` como gancho); si en el futuro se quiere sincronización o multi-usuario
  habría que revisar hacia una base de datos.

Tareas relacionadas: [[Panel de gestión de hosts y sesiones]],
[[Scripts de inicio por sesión]]. Enmarca y consume
[[ADR-0001 Credenciales en almacén nativo del SO]] y
[[ADR-0005 Autenticación SSH y verificación de host]].
