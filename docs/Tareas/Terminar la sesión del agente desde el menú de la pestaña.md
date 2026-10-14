---
Nombre: 'Terminar la sesión del agente desde el menú de la pestaña'
Estado: 'Hecha'
Resumen: 'Atajo en el menú de pulsación larga de una pestaña de terminal de nivel 3: "[x] Terminar en el destino" cierra la pestaña y termina esa instancia en el agente del destino, lo mismo que Terminar en el panel del agente. El agente y las demás sesiones siguen vivos. Se confirma dentro del propio menú.'
Decisiones: 'Pedido y aceptado por el usuario el 2026-10-15. Reemplaza la decisión visual que descartaba terminar desde la pestaña (ver Control del agente en la interfaz y terminar desde la pestaña). Nombre "Terminar en el destino", como en la lanzadera. Confirmación en el mismo menú (pregunta + [x] Sí / [<] No), sin diálogo. Se cierran todas las pestañas de esa instancia para que ninguna reconecte y la arranque de nuevo. Integrada en main el 2026-10-15.'
Bloqueada: []
Fecha de creación: 2026-10-15T00:51:29+02:00
Última modificación: 2026-10-15T00:51:29+02:00
---

# Terminar la sesión del agente desde el menú de la pestaña

## Objetivo

Terminar la sesión de una pestaña de nivel 3 sin ir a la lanzadera o al panel
del agente. Hoy hay que abrir el panel, buscar la sesión (o su instancia en
paralelo) y pulsar Terminar. Diseño en
[[Control del agente en la interfaz y terminar desde la pestaña]], que
reemplaza a [[Estado y control del agente en la interfaz]]. Amplía
[[Menú de la pestaña con pulsación larga]] y
[[Transparencia y control del agente en el destino]].

## Criterios de finalización

- El menú de una pestaña de terminal de nivel 3 muestra
  `[x] Terminar en el destino` en `danger`; no aparece en pestañas de otros
  niveles, de mouse pad ni de SFTP.
- Pulsarla sustituye la entrada por la pregunta, `[x] Sí` y `[<] No`; `No`
  vuelve al menú normal y tocar fuera descarta la confirmación.
- `Sí` cierra las pestañas de esa instancia y la sesión desaparece del agente
  (el panel ya no la lista); las demás instancias y el agente siguen vivos.
- Probado en el emulador contra el destino de pruebas.

## Verificación

2026-10-15, build de debug en el emulador `Pixel_9_Pro_XL` contra un
contenedor desechable de la imagen de `tools/test-sshd/` con el agente actual
(uno por sesión, ADR-0022):

- `:desktopApp:compileKotlin` y `:androidApp:assembleDebug` compilan.
- El menú de una pestaña de nivel 3 muestra `[x] Terminar en el destino`;
  `[<] No` vuelve al menú normal; `[x] Sí` cierra la pestaña de la instancia 2
  y el agente deja de listarla (`--status`: una sola sesión).
- La instancia 1 sigue conectada sin reconectar: su cliente en el destino es
  el mismo proceso antes y después.
- En el contenedor permanente, que tenía un agente de desarrollo anterior a
  ADR-0022, terminar una instancia (desde el menú o desde el panel, que no
  cambia) hacía caer el cliente de la otra. No pasa con el agente actual.

Integrada en `main` a petición del usuario el 2026-10-15; llega al Pixel con la
siguiente versión publicada.

## Resultado

`SessionsArea.kt`: entrada `[x] Terminar en el destino` en el menú de la
pestaña (solo terminal de nivel 3) con confirmación en el propio menú
(`TabMenuNote` para la pregunta), que llama a `AgentManager.terminate(session,
instance)`, lo mismo que Terminar en el panel. Decisión visual nueva
[[Control del agente en la interfaz y terminar desde la pestaña]], que
reemplaza a [[Estado y control del agente en la interfaz]].
