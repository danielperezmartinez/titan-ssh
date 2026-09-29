---
Nombre: 'Estado y control del agente en la interfaz'
Estado: 'Aceptada'
Ámbito: 'Layout'
Resumen: 'El estado del agente de nivel 3 se ve en la lanzadera de Sesiones y en un panel propio, nunca en la pestaña del terminal. En la lanzadera, cada sesión viva en el destino lleva una línea más ("viva en el destino · vista hace 3 h", o en warning "sin conectar desde hace 26 h") y sus acciones desplegables suman "Ver el agente del destino" y "Terminar en el destino" (danger, confirmación en línea). Una franja warning encima de la lista avisa si un agente pasa de 1 GB. Eliminar una sesión viva solo ofrece eliminarla y terminarla, o no eliminarla. El panel del agente es una pantalla secundaria con [<] + título: datos del agente, sus sesiones como filas con Abrir y Terminar, aviso de versión anterior y Detener el agente. Se llega desde la lanzadera, desde la franja de memoria y desde las acciones de cada host en Configuración → Hosts.'
Decisión: 'Estado y control del agente en la lanzadera de Sesiones y en un panel propio de pantalla secundaria; la pestaña del terminal y su franja de estado no cambian.'
Consecuencias: 'La pestaña abierta no ofrece ninguna forma de terminar la sesión en el destino: cerrar la pestaña nunca pierde trabajo y terminar exige ir a la lanzadera o al panel. Los datos del agente son los de la última consulta, así que siempre se muestra cuándo se consultaron.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-29T22:30:00+02:00
Última modificación: 2026-09-29T22:30:00+02:00
---

# Estado y control del agente en la interfaz

Decidida con el usuario el 2026-09-29 para
[[Transparencia y control del agente en el destino]]. Usa las filas de
[[Filas de lista con acciones contextuales desplegables]], la navegación de
[[Sesiones como inicio y Configuración desde la cabecera]] y la disciplina de
color de [[Vocabulario ASCII ampliado y disciplina de color]].

## Lanzadera de Sesiones

```
[>] titan (ruta A)                         [>] Lanzar
    user@servidor:22
    viva en el destino · vista hace 3 h

[>] web (ruta B)                           [>] Lanzar
    user@servidor:22
    sin conectar desde hace 26 h            ← warning
```

- Línea extra solo si la sesión está viva en el destino según la última
  consulta, en `mute`; en `warning` cuando lleva 24 horas seguidas sin cliente.
- Franja `warning` encima de la lista solo si algún agente pasa de 1 GB:
  `[-] El agente de servidor ocupa 1,2 GB · [>] Ver`.
- Acciones desplegables de la fila, además de Editar / Duplicar / Eliminar:
  `Ver el agente del destino` y `[x] Terminar en el destino` (`danger`,
  confirmación en línea).
- Eliminar una sesión viva: `¿Eliminar? Sigue viva en el destino ·
  [x] Eliminar y terminarla · [<] No`. No hay opción de dejarla viva, para que
  no queden sesiones huérfanas. Si el destino no responde, la sesión se
  elimina de la app y el panel la muestra como `pendiente de terminar` hasta
  la siguiente conexión a ese host, que la termina.

## Panel del agente

```
[<] Agente · servidor
    v0.1.0-beta.6 · encendido desde el 27/09 16:10 · 180 MB
    consultado hace 1 min                  [~] Actualizar

[+] titan (ruta A)       conectada ahora
[-] web (ruta B)         en segundo plano · último uso hace 3 h
[-] (sesión borrada)     huérfana · último uso hace 5 días

[x] Detener el agente (cerrará 3 sesiones)
```

- Pantalla secundaria con `[<]` + título, como Configuración.
- Cada sesión es una fila que despliega `Abrir` y `[x] Terminar`.
- Agente de una versión anterior: fila `warning` arriba,
  `[-] Versión anterior · [~] Actualizar agente`, que avisa de que cierra sus
  sesiones.
- `Detener el agente` en `danger`, con confirmación en línea que dice cuántas
  sesiones cierra.
- Se abre desde la acción de la lanzadera, desde la franja de memoria y desde
  las acciones de cada host en Configuración → Hosts.

## Lo que no cambia

- La pestaña del terminal y su franja de estado: ni menú del agente ni opción
  de terminar. La `[x]` de la pestaña solo la cierra y la sesión sigue viva.
