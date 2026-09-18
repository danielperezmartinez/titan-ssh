---
Nombre: "ConfigStore"
Tipo: "Contrato"
Área: "Configuración"
Feature: "Gestión de hosts"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/config/ConfigStore.kt"
Entrada pública: "im.gar.titanssh.config"
Resumen: "Contrato de persistencia (no secreta) de la configuración del área. load()/save(config) sobre un documento JSON en fichero app-privado, con escritura atómica. Se obtiene con createConfigStore() (expect/actual: solo el directorio base es específico de plataforma —Android filesDir, escritorio dir de config del SO—); la impl es compartida en jvmShared (JsonFileConfigStore). No persiste secretos: solo referencias (SecretRef) o alias de clave hardware, coherente con ADR-0001. Round-trip verificado contra fichero temporal."
Última modificación: 2026-09-18T15:30:00+02:00
---

# ConfigStore

Después de descubrir esta pieza en el catálogo, consulta el contrato en
[ConfigStore.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/config/ConfigStore.kt)
y la implementación compartida en
[JsonFileConfigStore.kt](../../shared/src/jvmSharedMain/kotlin/im/gar/titanssh/config/JsonFileConfigStore.kt),
que son la fuente de verdad. Los `actual` que eligen el directorio app-privado
están en
[ConfigStore.android.kt](../../shared/src/androidMain/kotlin/im/gar/titanssh/config/ConfigStore.android.kt)
y
[ConfigStore.desktop.kt](../../shared/src/desktopMain/kotlin/im/gar/titanssh/config/ConfigStore.desktop.kt).

Piezas relacionadas:

- [[Modelo de configuración]] — la forma `TitanConfig` que se serializa.
- [[ConfigController]] — dueño en memoria que carga una vez y persiste cada edición.
- [[SecretStore]] — custodia el material que el `ConfigStore` referencia pero
  nunca guarda.

Formalizado en [[ADR-0007 Modelo y persistencia de configuración]]; secretos por
referencia según [[ADR-0001 Credenciales en almacén nativo del SO]].
