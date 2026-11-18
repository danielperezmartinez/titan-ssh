---
Nombre: 'Controles del visor del escritorio remoto con barra movible'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'El visor del escritorio remoto tiene escala ajustar o 1:1, zoom y pantalla completa. En escritorio los controles van en la franja de estado (ajustar | 1:1, − zoom +, minimapa, Ctrl+Alt+Supr, completa); en el móvil, tras [#] vista, con pellizco para el zoom. El minimapa se oculta con su [x] y vuelve desde los controles. En la pantalla completa de escritorio hay una sola barra flotante que se pliega y despliega en su sitio, se arrastra por su asa a cualquier punto y, si el usuario lo elige, se oculta del todo plegada y vuelve al llevar el ratón al borde. Atajos Ctrl+Alt+Intro y Ctrl+Alt+rueda. Minimapa, barra (posición y ocultar), escala y zoom se recuerdan por sesión.'
Decisión: 'Escala ajustar o 1:1 más zoom, minimapa que se puede ocultar, controles en la franja en escritorio y tras [#] vista en el móvil, y en pantalla completa una única barra flotante plegable, movible y opcionalmente oculta.'
Consecuencias: 'El chip zoom Nx de Android desaparece. La sesión de escritorio remoto guarda escala, zoom, minimapa y la posición y el modo de la barra. Elegir monitor y calidad de imagen van en otras tareas.'
Reemplaza:
  - '[[Controles del visor del escritorio remoto]]'
Reemplazada por: []
Fecha de creación: 2026-11-18T22:30:00+01:00
Última modificación: 2026-11-18T22:30:00+01:00
---

# Controles del visor del escritorio remoto con barra movible

## Contexto

El visor ([[ADR-0029 Escritorio remoto propio a través del agente]]) siempre
encoge la pantalla del destino para que quepa en el hueco que deja la app. En
un portátil con la pantalla pequeña, un destino de 1920×1080 se ve al ~64 % y
el texto cuesta de leer. Solo Android tenía zoom (un chip `zoom Nx` en la
franja que va de 1x a 3x siguiendo al cursor) y no había pantalla completa.
El diseño se hizo con el usuario en un lienzo de diseño el 2026-11-18
(privado, fuera del repositorio). La tarea es
[[Pantalla completa y escala del escritorio remoto]].

Reemplaza a [[Controles del visor del escritorio remoto]] tras la primera
prueba del usuario en la canary (2026-11-18): el minimapa a veces tapa
contenido, la barra flotante puede tapar algo que haga falta pulsar, y la
pestañita y la barra desplegada parecían dos cosas distintas.

## Decisión

### Escala

- **Ajustar** (por defecto): toda la pantalla del destino cabe en el visor,
  como hoy.
- **1:1**: un píxel del destino es un píxel de la pantalla (en píxeles
  físicos), aunque no quepa. Con ratón, la vista se desplaza al dejarlo cerca
  de un borde; con el táctil, sigue al cursor del destino.
- **Minimapa**: cuando la imagen no cabe entera, una esquina enseña la
  pantalla entera en pequeño con la parte visible marcada. Se oculta con su
  `[x]` y vuelve con `[ ] minimapa` en los controles; se recuerda por sesión.
- **Zoom** sobre cualquiera de los dos, siguiendo al cursor como el de hoy.
  En escritorio se cambia con `−`/`+`; en el móvil con niveles fijos (1x a
  4x) o pellizcando.
- La escala y el zoom se recuerdan por sesión.

### Dónde van los controles

Según [[Franja de estado según la plataforma]]:

- **Escritorio**: en la franja, a la derecha de la
  [[Barra de calidad de la conexión en la franja de estado]]:
  `ajustar | 1:1` · `− 64% +` · `[x] minimapa` (solo si hay minimapa) ·
  `Ctrl+Alt+Supr` · `[ ] completa`.
- **Móvil**: en la franja solo `[#] vista · 2x`, que despliega un panel sobre
  la imagen, sin moverla ni encogerla, con los mismos controles en grupos que
  se parten en varias líneas y botones de 44 px.

### Pantalla completa

- **Escritorio**: la ventana pasa a pantalla completa y se ocultan la
  cabecera, las pestañas y la franja. Al entrar, un aviso breve dice cómo
  salir. Queda **una sola barra flotante** que se pliega y se despliega en
  su sitio:
  - **Plegada**: el asa `⋮⋮`, el indicador de red, el nombre de la sesión y
    `▸`.
  - **Desplegada**: la misma barra crece, con una animación corta, y suma los
    controles de la franja, `[ ] fijar` (no se pliega al irse el ratón) y
    `[ ] ocultar`. Se despliega al pasar el ratón por encima o al hacer clic.
  - **Movible**: se arrastra por el asa a cualquier punto de la pantalla, para
    que nunca tape algo que haga falta pulsar. Su posición se guarda como
    fracción del hueco que deja libre, así que al desplegarse crece hacia el
    lado donde hay sitio. Se recuerda por sesión.
  - **Ocultar** (opción del usuario, recordada por sesión): plegada, la barra
    desaparece del todo y queda una franja invisible en el centro del borde
    más cercano (arriba o abajo), bajo donde estaba la barra; al llevar el
    ratón ahí vuelve a desplegarse. Sin la opción, plegada sigue visible.
- **Móvil**: modo inmersivo de Android (sin barras del sistema ni de la app),
  con un botón `[≡]` que abre el mismo panel y otro que abre el teclado.

### Atajos (escritorio)

El teclado va al destino, así que el visor reserva combinaciones con
Ctrl+Alt, como los clientes RDP:

- `Ctrl+Alt+Intro`: pantalla completa sí/no.
- `Ctrl+Alt` + rueda del ratón sobre la imagen: zoom.
- `Esc` no sale de pantalla completa: va al destino.

Ajuste al implementar (2026-11-18): el diseño proponía también
`Ctrl+Alt+0`/`1`/`+`/`-`. Se quitan porque en Windows AltGr llega como
Ctrl+Alt y, con teclado español, `AltGr+1` escribe `|` y `AltGr++` escribe
`]`: esos atajos se comerían caracteres. `AltGr+Intro` no escribe nada, y la
rueda no choca con ningún carácter.

### Fuera de esta decisión

- Elegir qué monitor del destino ver: el agente captura todos juntos
  ([[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]); va
  en [[Elegir el monitor en el escritorio remoto]].
- La calidad de imagen: [[Calidad de imagen del escritorio remoto]].

## Alternativas consideradas

- Todos los controles tras un menú también en escritorio: diseñada y guardada
  en el lienzo como reserva por si la franja se llena; descartada de momento
  porque en escritorio sobra sitio.
- Pantalla completa saliendo con `Esc`: descartada, `Esc` lo necesita el
  destino.
- Pestañita fija y barra aparte (la primera versión): descartada, parecían
  dos cosas distintas, una se escondía y la otra no.
- Quitar la franja invisible del borde: descartada por el usuario; se queda
  para quien elige ocultar la barra.

## Consecuencias

- Positivas: en una pantalla pequeña se puede leer el destino (1:1 o zoom) y
  aprovechar toda la pantalla.
- Negativas / compromisos: Ctrl+Alt+Intro y Ctrl+Alt+rueda no llegan al
  destino mientras el visor tiene el foco.
