---
Nombre: 'SessionInstances'
Tipo: 'Modelo de dominio'
Área: 'Terminal'
Feature: 'Resiliencia'
Estado: 'Vigente'
Ámbito: 'Feature'
Fuente: 'shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionInstances.kt'
Entrada pública: 'io.github.danielperezmartinez.titanssh.terminal'
Resumen: 'Instancias de una sesión guardada en el destino (ADR-0020): la principal (número 1, con el id de la sesión guardada) y las paralelas desde el 2 (id <id>-p<n>), que nombran la sesión del agente (nivel 3) y la de tmux/screen (nivel 2). Da el id de una instancia (id, agentId), la instancia a la que corresponde un id del agente (instanceOf), el número libre más bajo para una paralela (nextParallel), el nombre que se muestra (label, "nombre · 2") y qué hace lanzar una sesión según las instancias abiertas en pestañas, las vivas en el destino y su ReopenMode (plan → LaunchPlan.Open o LaunchPlan.Ask). TabInstance es la instancia de una pestaña: una paralela nueva mustBeNew hasta que se engancha y, si su número ya estaba vivo, salta al siguiente (skipTaken).'
Última modificación: '2026-10-13T10:05:00+02:00'
---

# SessionInstances

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[SessionInstances.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/SessionInstances.kt);
su cobertura está en `SessionInstancesTest` (headless), y la de saltar números
ocupados en `AgentTransportTest` y `MultiplexerAutomationTest`.

Nace de [[Sesiones paralelas de una misma sesión guardada]] y aplica
[[ADR-0020 Sesiones paralelas de una misma sesión guardada]].

Quién la usa:

- [[SessionManager]] — `open(resolved, instance, mustBeNew)` crea la pestaña
  con su `TabInstance` y expone `openInstances`.
- [[AgentTransport]] — `requireFresh` y `occupied`, para que una paralela nueva
  nunca se enganche a una terminal que ya existía.
- [[AgentWatch]] — `AgentInsights.instancesOf` agrupa las sesiones del agente
  por sesión guardada; `AgentManager.terminate` cierra todas o una.
- La lanzadera (`Launcher.kt`) y el panel del agente (`AgentPanel.kt`).
