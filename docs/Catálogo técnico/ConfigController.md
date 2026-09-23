---
Nombre: "ConfigController"
Tipo: "Servicio"
Área: "Configuración"
Feature: "Gestión de hosts"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/ConfigController.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.config"
Resumen: "Dueño en memoria y observable del TitanConfig que respalda la UI de Configuración. Carga una vez del ConfigStore y expone state: StateFlow<TitanConfig>, loaded y lastError. Cada edición produce un config inmutable nuevo, actualiza el estado y persiste fire-and-forget (un fallo aparece en lastError sin perder la edición). CRUD de hosts/sesiones/grupos/snippets con integridad referencial (borrar host limpia ProxyJump, borrar grupo desasocia miembros) y duplicateSession() con ids nuevos. Clase multiplataforma simple (sin ViewModel), sirve a Android y escritorio."
Última modificación: 2026-09-24T12:00:00+02:00
---

# ConfigController

Después de descubrir esta pieza en el catálogo, consulta
[ConfigController.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/config/ConfigController.kt)
como fuente de verdad de su API y su semántica de integridad referencial.

Notas de contrato:

- **Observable**: `state`/`loaded`/`lastError` son `StateFlow`; la UI Compose
  colecciona el estado y no toca el `ConfigStore` directamente.
- **Persistencia perezosa**: escribe en un `scope` de corrutinas; la edición en
  memoria nunca se revierte por un fallo de guardado.
- **Integridad**: `deleteHost` limpia referencias ProxyJump; `deleteGroup`
  desasocia hosts/sesiones; `duplicateSession` regenera ids de sesión, scripts y
  túneles.

Piezas relacionadas: [[ConfigStore]] (persistencia), [[Modelo de configuración]]
(la forma que gobierna). Formalizado en
[[ADR-0007 Modelo y persistencia de configuración]].
