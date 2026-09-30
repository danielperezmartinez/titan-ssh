---
Nombre: 'Franja de estado del terminal solo para el estado y los scripts'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'La franja que va encima del terminal en cada pestaña es solo para información muy concreta: el estado de la conexión (marcador y color), el nivel de resiliencia efectivo (nivel 3 · agente, nivel 2 · tmux…), la acción reconectar cuando la pestaña reconecta, está caída o ha fallado, y el acceso [>] scripts para desplegarlos. Nada más entra en ella: la información de detalle va a su pantalla propia (el panel del agente, Configuración…). Regla del usuario (2026-09-30), que vio que casi todo acababa en esa franja y que crecía demasiado. Lo que ya tiene además (túneles y aviso del nivel 3) se queda de momento y se revisará en una tarea aparte.'
Decisión: 'La franja de estado del terminal solo muestra el estado de la conexión, el nivel de resiliencia, la acción reconectar y el acceso a los scripts; no se le añade nada más.'
Consecuencias: 'Cualquier dato o acción nueva de una sesión se diseña en otra pantalla, no en la franja. Los elementos que tiene hoy fuera de la regla no se quitan sin decidirlo con el usuario en Revisar el contenido de la franja de estado del terminal.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-30T21:15:00+02:00
Última modificación: 2026-09-30T21:30:00+02:00
---

# Franja de estado del terminal solo para el estado y los scripts

## Contexto

La franja que va encima del terminal (`StatusStrip` de [[TerminalView]]) empezó
con el estado de la conexión y el nivel de resiliencia. Cada tarea nueva le fue
añadiendo algo: la acción de reconectar, los túneles y el aviso del nivel 3
debajo. Al proponer los datos de las sesiones del agente
([[Detalle de las sesiones del agente en el panel]]), el usuario vio que casi
todo se intentaba meter ahí y fijó esta regla el 2026-09-30.

## Decisión

La franja solo contiene:

```
[+] Conectado · nivel 3 · agente                  [>] scripts
[-] Reconectando… (intento 3) · nivel 3 · agente   reconectar  [>] scripts
```

- **Estado de la conexión**: marcador y color de la fase (`Conectando…`,
  `Conectado`, `Reconectando…`, `Caída`, `Fallo`) o su detalle (el intento de
  reconexión, por ejemplo).
- **Nivel de resiliencia efectivo**: `nivel 3 · agente`, `nivel 2 · tmux`,
  `nivel 2 · screen` o el nivel base.
- **`reconectar`**, cuando la pestaña reconecta, está caída o ha fallado.
- **`[>] scripts`**, para desplegar los scripts de la sesión.

No se añade nada más. Un dato o una acción nueva de la sesión o del agente va
a la pantalla que le corresponde. Por ejemplo, el detalle de las sesiones del
agente va al panel del agente ([[Estado y control del agente en la interfaz]]).

## Lo que hay hoy fuera de la regla

Se queda como está hasta revisarlo con el usuario en
[[Revisar el contenido de la franja de estado del terminal]]:

- `[=] túneles activos/total` y su panel.
- El aviso del nivel 3 debajo de la franja (motivo de la degradación o
  `activar linger`), que se cierra con `[x]`.

## Precisión del usuario (2026-09-30)

La primera redacción dejaba `reconectar` fuera de la regla. El usuario aclaró
que su regla ya lo incluía, igual que el botón de scripts, así que se corrige
aquí mismo: la decisión no cambia, solo cómo se había recogido.

## Alternativas consideradas

- Seguir añadiendo a la franja lo que afecte a la pestaña: descartada. Crece
  sin límite y en el móvil no cabe.

## Consecuencias

- Positivas: la franja se mantiene corta y legible en el móvil, y cada dato
  tiene su sitio.
- Negativas / compromisos: algunos datos de la sesión exigen abrir otra
  pantalla para verlos.
