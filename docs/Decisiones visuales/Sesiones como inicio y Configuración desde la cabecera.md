---
Nombre: 'Sesiones como inicio y Configuración desde la cabecera'
Estado: 'Aceptada'
Ámbito: 'Layout'
Resumen: 'Desaparecen las pestañas de área "Configuración" y "Sesiones" de la cabecera. La app arranca en Sesiones, que es la pantalla de inicio; Configuración se abre con el glifo [*] (solo icono, sin etiqueta) a la derecha de [i], y vuelve a Sesiones con su [<] o tocando otra vez [*]. El glifo de la pantalla abierta va en accent.'
Decisión: 'Navegación de nivel superior con Sesiones por defecto y Configuración y Acerca de como pantallas secundarias abiertas desde glifos de la cabecera ([*] y [i]), con barra [<] + título y toggle en el glifo.'
Consecuencias: 'La cabecera ocupa una fila menos y el trabajo en vivo (terminales) queda siempre a un toque. Configuración pasa a ser una pantalla "de ida y vuelta" con barra [<] Configuración, igual que Acerca de. Las dos áreas conceptuales siguen vigentes; solo cambia cómo se llega a ellas.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-28T11:40:00+02:00
Última modificación: 2026-09-28T11:40:00+02:00
---

# Sesiones como inicio y Configuración desde la cabecera

Complementa —no reemplaza— a
[[Arquitectura de dos áreas Configuración y Sesiones]]: las dos áreas se
mantienen, cambia la navegación entre ellas. Pedida por el usuario el
2026-09-28 ([[Quitar las pestañas de área y abrir Configuración desde la cabecera]]).

## Contexto

La cabecera tenía el título, el glifo `[i]` y debajo una barra de dos pestañas
de área (Configuración / Sesiones), con Configuración seleccionada al abrir la
app. En el uso diario lo habitual es lanzar o retomar una sesión; la
configuración se toca de vez en cuando.

## Decisión

- **Sin pestañas de área.** La cabecera es una sola fila: `titan-ssh` a la
  izquierda y, a la derecha, `[i]` y `[*]`.
- **Sesiones es la pantalla de inicio** (lanzadera si no hay pestañas abiertas,
  terminal si las hay).
- **`[*]` abre Configuración.** Solo glifo, sin etiqueta (petición del
  usuario). Se eligió `[*]` porque el asterisco es el "engranaje" ASCII y
  respeta el vocabulario de marcadores entre brackets (añadido en
  [[Vocabulario ASCII ampliado y disciplina de color]]). Va a la derecha de
  `[i]`, con el mismo `GlyphButton` y zona táctil.
- **Color:** ambos glifos en `mute`; el de la pantalla abierta, en `accent`
  (selección activa = interacción).
- **Volver:** Configuración lleva la misma barra `[<] Configuración` que
  `[<] Acerca de`. Además, tocar el glifo de la pantalla abierta vuelve a
  Sesiones (toggle), y tocar el otro salta directamente a su pantalla.

## Alternativas consideradas

- **Glifo con etiqueta** (`[*] Ajustes`) — descartado: el usuario lo quiere
  solo icono.
- **Otros glifos:** `[#]` y `[=]` ya tienen significado (grupo, documento);
  `[~]` o `[%]` no evocan ajustes.
- **Solo toggle, sin barra `[<]`** — descartado: la barra es el patrón de
  vuelta de las demás pantallas secundarias y no depende de descubrir el
  toggle.

## Consecuencias

- Positivas: cabecera más compacta; las sesiones siempre a mano; Configuración
  y Acerca de se comportan igual.
- Negativas / compromisos: el estado de la pantalla de Sesiones (lanzadera
  abierta, pantalla partida) se pierde al ir a Configuración y volver, igual
  que antes al cambiar de pestaña; las sesiones abiertas no se ven afectadas.
- Aplica a Android y escritorio: es UI compartida de `commonMain`
  (`AppShell.kt`).
