---
Nombre: "BackgroundConnections"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Resiliencia"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/BackgroundConnections.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Aplica ADR-0023 a las pestañas abiertas. request (KeepAliveRequest: needed y connectedTabs) pide a la plataforma un servicio en primer plano mientras alguna pestaña perdería algo sin su conexión (SessionTab.keepAlive); con él todas siguen conectadas. Si ninguna lo necesita y la app lleva pauseAfterMillis (60 s) en segundo plano, pausa todas (SessionTab.pause) y al volver las reanuda (resume). La plataforma informa de la visibilidad con setForeground; en escritorio nunca se llama y nada se pausa. En la UI lo cablean AppShell con AppVisibilityEffect y KeepAliveService (expect: en Android, ciclo de vida de la actividad y el KeepAliveService de la app con tipo specialUse; en escritorio, nada)."
Última modificación: '2026-10-14T23:30:00+02:00'
---

# BackgroundConnections

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[BackgroundConnections.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/BackgroundConnections.kt),
la regla `needsConnection` y `pause`/`resume` en
[SessionTab.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionTab.kt),
y la parte de plataforma en `ui/BackgroundPlatform.kt` (expect) con su
`actual` de Android y el servicio `androidApp/.../KeepAliveService.kt`.

Contrato y colaboradores:

- **Decisión**: [[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]].
- **Entrada**: las pestañas de [[SessionManager]] (`tabs`) y la visibilidad de
  la app (`setForeground`).
- **Salida**: `request` para el servicio en primer plano de la plataforma, y
  las llamadas a `pause`/`resume` de cada pestaña.
- **Android**: `AppVisibilityEffect` sigue `ON_START`/`ON_STOP` de la
  actividad (bloquear la pantalla la para). `KeepAliveService` arranca el
  servicio con la app delante, actualiza el número de sesiones y lo para; vuelve
  a publicar la notificación en cada `ON_RESUME`.
- **Tests**: `SessionTabBackgroundTest` (reloj virtual).

Entregado en [[Conexiones en segundo plano según lo que pueda perder cada pestaña]].
