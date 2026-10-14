---
Nombre: 'Control del agente en la interfaz y terminar desde la pestaña'
Estado: 'Aceptada'
Ámbito: 'Layout'
Resumen: 'Sustituye a Estado y control del agente en la interfaz y mantiene todo lo que decía salvo un punto: el menú de pulsación larga de una pestaña de terminal de nivel 3 ofrece "[x] Terminar en el destino" (danger), que cierra la pestaña y termina esa instancia en el agente, como Terminar en el panel. Se confirma dentro del mismo menú ("¿Terminar? Se cierra lo que tenga en marcha" · [x] Sí · [<] No). El estado del agente sigue sin verse en la pestaña ni en su franja de estado, y la [x] de la pestaña sigue cerrándola sin terminar nada.'
Decisión: 'Estado y control del agente en la lanzadera de Sesiones y en un panel propio; además, el menú de la pestaña de nivel 3 ofrece terminar esa instancia en el destino, con confirmación en el propio menú.'
Consecuencias: 'Terminar una sesión ya no exige salir de la pestaña. Cerrar con la [x] sigue sin perder trabajo, y terminar pide dos toques en el menú. Se cierran todas las pestañas de esa instancia, para que ninguna reconecte y la vuelva a arrancar. Si el destino no responde, queda pendiente de terminar como al terminar desde el panel.'
Reemplaza:
  - '[[Estado y control del agente en la interfaz]]'
Reemplazada por: []
Fecha de creación: 2026-10-15T00:51:29+02:00
Última modificación: 2026-10-15T00:51:29+02:00
---

# Control del agente en la interfaz y terminar desde la pestaña

Decidida con el usuario el 2026-10-15 para
[[Terminar la sesión del agente desde el menú de la pestaña]]. Reemplaza a
[[Estado y control del agente en la interfaz]] (2026-09-29), que descartaba
cualquier forma de terminar desde la pestaña. Lo demás de aquella decisión se
conserva tal cual y se recoge aquí para que esta sea la única fuente de verdad.
Usa las filas de [[Filas de lista con acciones contextuales desplegables]], el
menú de [[Menú de la pestaña con pulsación larga]] y la disciplina de color de
[[Vocabulario ASCII ampliado y disciplina de color]].

## Contexto

Para terminar la sesión de una pestaña abierta había que ir a la lanzadera o al
panel del agente, buscar la sesión (o la instancia en paralelo) y pulsar
Terminar. El usuario pidió un atajo en el menú de la pestaña. La decisión
anterior lo descartaba para que desde la pestaña nunca se perdiera trabajo por
accidente. Con la confirmación en el propio menú ese riesgo se cubre.

## Decisión

### Menú de la pestaña (lo que cambia)

```
[~] Renombrar
[+] Abrir en paralelo
[d] Abrir explorador de archivos
[x] Terminar en el destino          ← danger

  pulsada:
¿Terminar? Se cierra lo que tenga en marcha     ← mute
[x] Sí                              ← danger
[<] No
```

- Solo en pestañas de terminal cuya sesión es de nivel 3 (agente).
- La acción termina **esa instancia** (ADR-0020), igual que Terminar en el
  panel del agente: cierra todas las pestañas que la muestran y después la
  sesión en el agente. Las demás instancias y el agente siguen vivos.
- Confirmación en el mismo menú: la entrada se sustituye por la pregunta y por
  `[x] Sí` / `[<] No`. `[<] No` vuelve al menú normal; tocar fuera lo cierra y
  descarta la confirmación.
- Si el destino no responde, la sesión queda `pendiente de terminar` y la
  siguiente conexión a ese host la termina.
- La `[x]` de la pestaña sigue solo cerrándola: la sesión sigue viva.
- La pestaña y su franja de estado siguen sin mostrar el estado del agente.

### Lanzadera de Sesiones (sin cambios)

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
  `[@] Ver el agente del destino` y `[x] Terminar en el destino` (`danger`,
  confirmación en línea). Terminar solo aparece si la última consulta muestra
  la sesión viva.
- Eliminar una sesión viva: `¿Eliminar? Sigue viva en el destino ·
  [x] Eliminar y terminarla · [<] No`. No hay opción de dejarla viva, para que
  no queden sesiones huérfanas. Si el destino no responde, la sesión se
  elimina de la app y el panel la muestra como `pendiente de terminar` hasta
  la siguiente conexión a ese host, que la termina. Eliminar una sesión de
  nivel 3 la termina siempre en el destino, aunque no se sepa si sigue viva.

### Panel del agente (sin cambios)

```
[<] Agente · servidor
    v0.1.0-beta.6 · encendido desde el 27/09 16:10 · 180 MB
    consultado hace 1 min                  [>] Consultar

[+] titan (ruta A)       conectada ahora
[-] web (ruta B)         en segundo plano · último uso hace 3 h
[-] (sesión borrada)     huérfana · último uso hace 5 días

[x] Detener el agente (cerrará 3 sesiones)
```

- Pantalla secundaria con `[<]` + título, como Configuración.
- Cada sesión es una fila que despliega `Abrir`, `[~] Renombrar` y
  `[x] Terminar`.
- Una sesión muestra un solo estado: `conectada ahora`,
  `en segundo plano · último uso hace X` o, pasadas 24 horas sin cliente,
  `sin conectar desde hace X` (marcador en warning); una huérfana antepone
  `huérfana`, y se añade la memoria de su shell cuando el destino la da.
- Agente de otra versión: fila `warning` arriba, `Agente de otra versión: X`
  con `[>] Actualizar`.
- `Detener el agente` en `danger`, con confirmación en línea que dice cuántas
  sesiones cierra.
- Se abre desde la acción de la lanzadera, desde la franja de memoria y desde
  las acciones de cada host en Configuración → Hosts (la fila sigue editando
  al pulsarla; su marcador o la pulsación larga despliegan `[~] Editar` y
  `[@] Ver el agente del destino`).

## Alternativas consideradas

- Mantener la decisión anterior (sin terminar en la pestaña): más seguro, pero
  obliga a dar un rodeo por la lanzadera o el panel para algo habitual.
- Diálogo modal para confirmar: tapa la terminal; el proyecto confirma lo
  destructivo en línea.
- Terminar sin confirmación: un toque de más en el menú perdería trabajo.

## Consecuencias

- Positivas: terminar la sesión de la pestaña está a un menú de distancia.
- Negativas / compromisos: el menú de la pestaña ya puede perder trabajo,
  aunque pide confirmarlo. Cerrar con la `[x]` sigue siendo la forma segura.
