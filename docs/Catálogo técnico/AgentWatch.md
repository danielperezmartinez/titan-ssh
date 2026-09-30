---
Nombre: 'AgentWatch'
Tipo: 'Servicio'
Área: 'Terminal'
Feature: 'Resiliencia'
Estado: 'Vigente'
Ámbito: 'Aplicación'
Fuente: 'shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentWatch.kt'
Entrada pública: 'io.github.danielperezmartinez.titanssh.terminal'
Resumen: 'Lo que la app sabe de los agentes de sus destinos y cómo actúa sobre ellos. AgentWatch guarda por AgentKey (usuario@host:puerto, porque el agente es por usuario) la última observación (AgentStatusReport + hora de la app) y los cierres pendientes; lo persiste AgentWatchStore (agents.json junto a la config, JsonFileAgentWatchStore en jvmShared). sync(key, control) cierra los pendientes y registra el estado; un informe running que ya no lista un pendiente lo olvida. Es el AgentObserver de SessionTab: al atender el agente una pestaña, sincroniza por esa misma conexión. AgentInsights deriva los avisos (solo informan): memoria por encima de 1 GiB, sesión abandonada tras 24 h sin cliente (reloj del destino + tiempo desde la consulta), agente de otra versión, y formatea duraciones y tamaños en español; sessionDetails arma las líneas del detalle de una sesión para el panel, con las fechas pasadas al reloj de la app (toAppClock). AgentHostAccess abre una conexión corta sin pestaña (clave de host nueva rechazada, no preguntada), instala el agente y sincroniza. AgentManager reúne las acciones explícitas del usuario: refresh, terminate (cierra antes las pestañas de la sesión), delete (borra la sesión guardada y, si es de nivel 3, la termina), closeAgentSession y stopAgent, y refreshWithPreviews, que en la misma conexión pide la vista previa de cada sesión y la guarda solo en memoria (previews), porque es lo que mostraba la terminal; toda terminación pasa por los cierres pendientes, así que si el destino no responde se completa en la siguiente conexión.'
Última modificación: 2026-09-30T22:00:00+02:00
---

# AgentWatch

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[AgentWatch.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentWatch.kt),
[AgentHostAccess.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentHostAccess.kt)
y
[AgentManager.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentManager.kt);
su cobertura está en `AgentWatchTest` (headless) y
`AgentControlIntegrationTest` (host real, opt-in).

Nace de [[Transparencia y control del agente en el destino]]. Nada aquí cierra
una sesión por su cuenta ([[ADR-0014 Sesiones del agente sin caducidad]]): solo
las que el usuario termina o borra. La interfaz que lo muestra (lanzadera y
panel del agente) está en [[Estado y control del agente en la interfaz]].

Piezas relacionadas:

- [[AgentControl]] — las órdenes que ejecuta en el destino.
- [[SessionManager]] — `closeTabsOf` y el `agentObserver` que recibe cada
  pestaña.
- [[ConfigController]] — el borrado de la sesión guardada.
