---
Nombre: 'Controles del visor del escritorio remoto'
Estado: 'Reemplazada'
Ámbito: 'Componente'
Resumen: 'El visor del escritorio remoto gana escala y pantalla completa. Modos de escala: ajustar a la ventana (el de hoy) o tamaño real 1:1, que se desplaza siguiendo al cursor y enseña un minimapa; más zoom sobre cualquiera de los dos. En escritorio los controles van en la franja de estado (ajustar | 1:1, − zoom +, Ctrl+Alt+Supr, [ ] completa); en pantalla completa la ventana ocupa la pantalla sin cabecera, pestañas ni franja, y los controles pasan a una barra flotante que aparece al llevar el ratón al borde de arriba. En el móvil, la franja solo lleva [#] vista, que abre un panel con los mismos controles en grupos que se parten en varias líneas; el zoom también se hace pellizcando, y la pantalla completa es el modo inmersivo de Android con un botón [≡] para el panel. Atajos Ctrl+Alt+Intro y Ctrl+Alt+rueda, porque el teclado va al destino.'
Decisión: 'Escala ajustar o 1:1 más zoom, pantalla completa con barra flotante, controles en la franja en escritorio y tras [#] vista en el móvil, y atajos Ctrl+Alt+Intro (pantalla completa) y Ctrl+Alt+rueda (zoom).'
Consecuencias: 'El chip zoom Nx de la franja de Android desaparece: el zoom pasa al panel y al pellizco. El zoom llega también a escritorio. La escala y el zoom se recuerdan por sesión; la pantalla completa no. Elegir qué monitor ver necesita al agente y va en otra tarea.'
Reemplaza: []
Reemplazada por:
  - '[[Controles del visor del escritorio remoto con barra movible]]'
Fecha de creación: 2026-11-18T20:25:00+01:00
Última modificación: 2026-11-18T22:30:00+01:00
---

# Controles del visor del escritorio remoto

## Contexto

El visor ([[ADR-0029 Escritorio remoto propio a través del agente]]) siempre
encoge la pantalla del destino para que quepa en el hueco que deja la app. En
un portátil con la pantalla pequeña, un destino de 1920×1080 se ve al ~64 % y
el texto cuesta de leer. Solo Android tenía zoom (un chip `zoom Nx` en la
franja que va de 1x a 3x siguiendo al cursor) y no había pantalla completa.
El diseño se hizo con el usuario en un lienzo de diseño el 2026-11-18
(privado, fuera del repositorio). La tarea es
[[Pantalla completa y escala del escritorio remoto]].

## Decisión

### Escala

- **Ajustar** (por defecto): toda la pantalla del destino cabe en el visor,
  como hoy.
- **1:1**: un píxel del destino es un píxel de la pantalla (en píxeles
  físicos), aunque no quepa. La vista se desplaza para seguir al cursor del
  destino y una esquina enseña un minimapa con la parte visible.
- **Zoom** sobre cualquiera de los dos, siguiendo al cursor como el de hoy.
  En escritorio se cambia con `−`/`+`; en el móvil con niveles fijos (1x a
  4x) o pellizcando.
- La escala y el zoom se recuerdan por sesión.

### Dónde van los controles

Según [[Franja de estado según la plataforma]]:

- **Escritorio**: en la franja, a la derecha de la
  [[Barra de calidad de la conexión en la franja de estado]]:
  `ajustar | 1:1` · `− 64% +` · `Ctrl+Alt+Supr` · `[ ] completa`.
- **Móvil**: en la franja solo `[#] vista · 2x`, que despliega un panel sobre
  la imagen, sin moverla ni encogerla, con los mismos controles en grupos que
  se parten en varias líneas y botones de 44 px.

### Pantalla completa

- **Escritorio**: la ventana pasa a pantalla completa y se ocultan la
  cabecera, las pestañas y la franja. Arriba en el centro queda una pestañita
  con el estado y el nombre de la sesión; al llevar el ratón al borde de
  arriba se despliega una barra flotante con los controles de la franja y
  `[x] salir de completa`. Al entrar, un aviso breve dice cómo salir.
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

## Consecuencias

- Positivas: en una pantalla pequeña se puede leer el destino (1:1 o zoom) y
  aprovechar toda la pantalla.
- Negativas / compromisos: Ctrl+Alt+Intro y Ctrl+Alt+rueda no llegan al
  destino mientras el visor tiene el foco.

## Reemplazada (2026-11-18)

Tras la primera prueba en la canary, el usuario pidió poder ocultar el
minimapa y mover la barra de la pantalla completa, y que la pestañita y la
barra desplegada fueran una sola cosa: ver
[[Controles del visor del escritorio remoto con barra movible]].
