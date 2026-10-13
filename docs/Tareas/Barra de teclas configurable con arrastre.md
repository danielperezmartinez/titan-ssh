---
Nombre: 'Barra de teclas configurable con arrastre'
Estado: Hecha
Resumen: 'La barra de teclas de encima del teclado (terminal y mouse pad) pasa a ser un único componente con un catálogo de teclas; un botón [*] junto a [kbd] abre una pantalla donde el usuario la reordena, añade y quita teclas arrastrándolas. Una sola barra para toda la app, guardada en los ajustes.'
Decisiones: 'Un componente compartido y la misma barra en la terminal y en el mouse pad; las teclas que solo tienen sentido en un escritorio remoto (Win, ImpPnt, Menú, multimedia) salen atenuadas en la terminal. Una barra para toda la app, no por host. Los modificadores pegajosos (Ctrl, Alt, Mayús) siguen combinándose con el teclado normal; los atajos de un toque (^C, ^R…) son un añadido, no un sustituto. Pegar es una tecla más, movible y quitable. Sin configurar, la barra es la de la terminal de antes más Mayús y Pegar.'
Bloqueada: []
Fecha de creación: 2026-10-13T07:47:12+02:00
Última modificación: 2026-10-13T08:55:00+02:00
---

# Barra de teclas configurable con arrastre

## Objetivo

Hasta ahora la barra de teclas accesorias era fija en el código y había dos
distintas: la de la terminal (Esc, Tab, Ctrl, Alt, flechas, `| / - ~`, Pegar)
y la del mouse pad (Ctrl, Alt, Mayús, Win, navegación, F1-F12, multimedia). El
usuario quiere elegir qué teclas lleva y en qué orden.

- Un botón `[*]` a la izquierda de `[kbd]` abre la pantalla "Barra de teclas".
- Arriba, la barra tal como queda; abajo, todas las teclas disponibles por
  grupos (modificadores, control, navegación, atajos, funciones, símbolos,
  escritorio remoto, acciones).
- Mantener pulsada una tecla y arrastrarla: de abajo a la barra la añade donde
  se suelta; dentro de la barra la mueve; de la barra hacia abajo la quita. Un
  toque en una tecla de abajo la añade al final. `Restablecer` vuelve a la
  barra por defecto.
- Un único componente de barra para la terminal y el mouse pad, con la misma
  configuración.

## Criterios de finalización

- La terminal y el mouse pad pintan la barra con el mismo componente y la
  misma lista de teclas, sacada de los ajustes de la app.
- La pantalla de configuración permite añadir, mover y quitar teclas
  arrastrando, y restablecer la barra por defecto; los cambios se guardan y
  sobreviven a reiniciar la app.
- Ctrl, Alt y Mayús pegajosos siguen aplicándose a lo que se escribe con el
  teclado del sistema, como antes.
- Las teclas nuevas mandan la secuencia correcta en la terminal (F1-F12,
  Insert, Mayús+Tab, combinaciones con flechas) y en el mouse pad.
- Tests de la traducción de teclas y de la resolución de la barra guardada.
- Probado en el emulador Android con capturas.

## Verificación

- `:shared:desktopTest` completo en verde, con `KeyBarTest` (8: catálogo,
  barra por defecto, resolución de la barra guardada, bytes para la terminal,
  tramas para el escritorio, teclas solo de escritorio, hueco de destino en
  filas que saltan de línea, pegar en el mouse pad) y `TerminalKeysTest`
  ampliado (F1-F12, Insert, Mayús+Tab, modificadores al estilo xterm, texto
  con modificadores pegajosos).
- `:androidApp:assembleDebug` y prueba en el emulador `Pixel_9_Pro_XL` contra
  el servidor SSH de pruebas (2026-10-13):
  - `[*]` aparece a la izquierda de `[kbd]` y abre "Barra de teclas".
  - Arrastrar F5 del catálogo entre Tab y Ctrl la inserta ahí; sacar una
    tecla de la barra la quita; arrastrar Pegar al principio la mueve; un
    toque en ^C y en Win las añade al final. La barra guardada sobrevive a
    reiniciar la app.
  - En la terminal, `cat -v` recibe `ESC [ 15 ~` con F5, `ESC [ 15 ; 5 ~` con
    Ctrl fijado + F5 y `ESC x` con Alt fijado + `x` escrita con el teclado;
    Ctrl fijado + `c` escrita con el teclado corta `cat`, y el atajo ^C corta
    un `sleep 30`. Win sale atenuada y no hace nada.
- Durante la prueba apareció un fallo: al quitar una tecla se perdía la
  añadida justo antes (el callback `::endDrag` se quedaba con la barra de la
  primera composición). Corregido leyendo la barra con `rememberUpdatedState`.
- El mouse pad no se probó en vivo (inyectaría entrada en el escritorio del
  usuario); su reparto de teclas está cubierto por `KeyBarTest`.

## Resultado

- [[KeyBar]] (`terminal/KeyBar.kt`): catálogo de teclas con id estable, grupo
  y acción, y la traducción a bytes de terminal y a tramas del agente.
- [[KeyBarView]] (`ui/KeyBarView.kt`): `KeyBarRow`, la barra que usan
  [[TerminalView]] y [[MousepadView]], y `KeyBarEditor`, la pantalla de
  arrastre que abre `AppShell`.
- [[TerminalKeys]] gana F1-F12, Insert, Mayús+Tab, `special(key, modifiers)`
  y `typed(text, modifiers)`; `MousepadTyping.pasted` teclea el portapapeles en
  el mouse pad.
- La barra se guarda en `AppSettings.keyBar` ([[Modelo de configuración]]) con
  `ConfigController.setKeyBar`.
- Diseño en [[Barra de teclas configurable por arrastre]].
- Cambio para el mouse pad: sin configurar, su barra pasa a ser la común (la
  de la terminal más Mayús y Pegar); Win, F1-F12 y las multimedia se añaden
  desde el editor.
