---
Nombre: 'Sesiones paralelas de una misma sesión guardada'
Estado: 'En curso'
Resumen: 'Petición del usuario (2026-10-13). Hoy, abrir dos veces la misma sesión guardada con nivel 2 o 3 engancha las dos pestañas a la misma terminal del destino. Objetivo: que el usuario elija al lanzarla si se reengancha a una instancia existente o abre una sesión paralela independiente. Las paralelas se numeran (· 2, · 3…) y se puede volver a ellas igual que a la principal. Incluye un ajuste por sesión guardada para no preguntar (preguntar · reengancharse · paralela), y agrupar las instancias en la lanzadera y en el panel del agente.'
Decisiones: 'Diseño aceptado por el usuario el 2026-10-13 y recogido en [[ADR-0020 Sesiones paralelas de una misma sesión guardada]]. Las paralelas se numeran; renombrarlas va en [[Renombrar las pestañas y las sesiones activas]]. Se incluye el ajuste por sesión "Al abrirla de nuevo". Abrir una paralela desde la propia pestaña necesita un menú de acciones en la pestaña, que hoy no existe, y se hace junto con el renombrado.'
Bloqueada: []
Fecha de creación: 2026-10-13T09:45:00+02:00
Última modificación: 2026-10-13T10:10:00+02:00
---

# Sesiones paralelas de una misma sesión guardada

## Objetivo

Que el usuario decida, al abrir una sesión guardada que ya tiene instancias
abiertas o vivas en el destino, si se reengancha a una de ellas (lo de hoy,
para no perder trabajo tras una caída) o abre una sesión paralela
independiente, sin tener que duplicar la sesión guardada. El diseño está en
[[ADR-0020 Sesiones paralelas de una misma sesión guardada]].

## Situación de partida (2026-10-13)

- Nivel 3: `SessionTab` usa `agentSessionId = resolved.session.id`; el agente
  ve `titan-<id>` (`AgentTransport.sanitizeId`).
- Nivel 2: `StartScriptAutomation` entra en `titan-<id>`
  (`TerminalMultiplexer.sessionName`).
- La lanzadera (`Launcher.kt`) abre siempre con `SessionManager.open`, sin
  preguntar, y su nota "viva en el destino" sale de `AgentInsights.sessionOf`,
  que solo conoce la principal.
- El panel del agente (`AgentPanel.kt`) relaciona cada sesión del agente con
  su sesión guardada por id exacto: una paralela saldría como huérfana.
- `AgentManager.terminate`/`delete`/`isLive` y `SessionManager.closeTabsOf`
  solo conocen el id de la sesión guardada.

## Plan

1. **Modelo**: instancia `1` (principal, id de siempre) y `n ≥ 2`
   (`<id>-p<n>`); función única que da el id de instancia y otra que, dado un
   id del agente, devuelve la sesión guardada y la instancia.
2. **Pestaña**: `SessionTab` lleva su instancia; el nivel 3 y el nivel 2 usan
   el id de la instancia. Una paralela nueva exige terminal nueva en su primera
   conexión: si el agente o tmux dicen que ya existía, prueba el número
   siguiente.
3. **Ajuste** `Session.reopen` (`ASK` por defecto · `ATTACH` · `PARALLEL`) en
   el editor de la sesión, solo para nivel 2 y 3.
4. **Lanzadera**: al lanzar con instancias abiertas o vivas, según el ajuste,
   despliega bajo la fila la elección (reengancharse a cada instancia, con su
   estado, o `Nueva sesión paralela`). La nota de "viva en el destino" cuenta
   todas las instancias.
5. **Panel del agente**: las paralelas salen como `<nombre> · n`, se abren en
   esa instancia y se pueden cerrar una a una.
6. **Terminar y eliminar**: cierran las pestañas y las sesiones del agente de
   todas las instancias.
7. **Título de pestaña**: `<nombre> · n`.

## Criterios de finalización

- Con nivel 3 y nivel 2, lanzar una sesión guardada ya abierta ofrece
  reengancharse o abrir una paralela, y la paralela es una terminal
  independiente que ejecuta sus scripts de inicio.
- Una paralela se recupera tras cerrar su pestaña, tras reiniciar la app y
  desde otro dispositivo.
- El ajuste "Al abrirla de nuevo" se guarda y se respeta.
- El panel del agente agrupa las paralelas con su sesión guardada (no salen
  como huérfanas); terminar y eliminar cierran todas las instancias.
- Nivel 1 y mouse pad siguen como antes.
- Tests unitarios del modelo, `./gradlew` verde y prueba en el emulador
  contra el contenedor de pruebas, con capturas para el usuario.

## Implementación (2026-10-13)

- `SessionInstances.kt` (nuevo, en [[SessionInstances]]): ids de instancia,
  `instanceOf`, `nextParallel`, `label`, `plan` (`LaunchPlan.Open`/`Ask`) y
  `TabInstance`.
- `Session.reopen: ReopenMode` (`ASK` por defecto); en el editor, "Al abrirla
  de nuevo" solo con nivel 2 o 3.
- `SessionTab` lleva su `TabInstance`; el nivel 3 usa su id en el `HELLO` y
  el nivel 2 en el nombre de tmux/screen (vía `ShellIo.instance`). Una
  paralela nueva exige terminal nueva en su primera conexión:
  `AgentTransport(requireFresh)` se despide si el agente ya la tenía
  (`occupied`) y la pestaña prueba el número siguiente; en nivel 2,
  `StartScriptAutomation` salta los nombres que ya existen.
- **Arreglo de paso**: `tmux has-session -t <nombre>` también acepta un
  prefijo, así que la principal se habría dado por viva con solo su paralela
  `…-p2`. Ahora se pregunta por el nombre exacto (`-t '=<nombre>'`).
- Lanzadera: según el plan, abre directamente o despliega la elección bajo la
  fila ("Reengancharse a la principal", "Reengancharse a <nombre> · 2", "Nueva
  sesión paralela"). La nota "viva en el destino" cuenta todas las instancias
  sin pestaña. Pestañas con título `<nombre> · n`.
- Panel del agente: cada sesión del agente se relaciona con su sesión guardada
  e instancia; "Abrir" abre esa instancia y "Terminar" cierra solo esa.
  "Terminar en el destino" y eliminar desde la lanzadera cierran todas.

## Verificación

- `:shared:desktopTest` completo en verde (348 tests), con
  `SessionInstancesTest` nuevo y casos nuevos en `AgentTransportTest` (una
  paralela nueva no se engancha a una sesión viva) y
  `MultiplexerAutomationTest` (salta números vivos; la principal no se
  confunde con su paralela). Compilan `:androidApp:compileDebugKotlin` y
  `:desktopApp:compileKotlin`.
- Emulador `Pixel_9_Pro_XL`, build de debug instalada al lado de las de otras
  sesiones, contra el contenedor de pruebas (se le instaló tmux para el
  nivel 2):
  - Nivel 3: la primera vez abre sin preguntar; la segunda ofrece la elección.
    La paralela es una terminal nueva (ejecuta sus scripts de inicio) y el
    agente lista `titan-session-demo-agent` y `…-p2`. Al cerrar su pestaña,
    la lanzadera la muestra "viva en el destino, sin pestaña" y al
    reengancharse conserva su contenido sin repetir los scripts.
  - Panel del agente: muestra `demo (agente) · 2`; terminarla desde ahí cierra
    solo esa sesión y su pestaña.
  - Nivel 2: la paralela crea `titan-session-demo-tmux-p2`; con el ajuste en
    "paralela" se guarda `reopen: PARALLEL` y lanzar abre `…-p3` sin preguntar.
  - Tras forzar el cierre de la app y volver a abrirla, la lanzadera sigue
    ofreciendo la elección con lo que sabía del agente.
- Pendiente: que el usuario lo pruebe, y reengancharse a una paralela desde
  otro dispositivo (mismo mecanismo que la principal, no probado aún).

## Limitaciones conocidas

- En nivel 2 la app no lista las sesiones de tmux/screen del destino: solo
  conoce las paralelas abiertas en pestañas. Una paralela de nivel 2 cerrada
  sin terminar sigue viva en tmux pero no aparece en la lanzadera, y una
  paralela nueva salta su número.
- Si se elimina la sesión guardada con el destino sin responder, solo se
  cierran las instancias que la app conocía (ver la ADR).

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
