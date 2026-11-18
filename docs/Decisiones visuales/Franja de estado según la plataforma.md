---
Nombre: 'Franja de estado según la plataforma'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'La regla de la franja de estado de cada pestaña (terminal, SFTP, mouse pad y escritorio remoto) depende de la plataforma. En el móvil sigue la regla anterior: estado de la conexión, nivel de resiliencia, reconectar y un único acceso que despliega (como [>] scripts); cualquier acción nueva entra solo tras un botón de menú. En escritorio, donde la franja tiene sitio de sobra, puede llevar a la vista los controles de la sesión (p. ej. la escala y la pantalla completa del escritorio remoto); si dejan de caber, se recogen en el mismo botón de menú que en el móvil.'
Decisión: 'Móvil: la franja solo muestra el estado y accesos que despliegan, y lo nuevo va tras un botón de menú. Escritorio: la franja puede mostrar los controles de la sesión directamente, con el menú del móvil como alternativa cuando no quepan.'
Consecuencias: 'Cada control nuevo de una sesión se diseña dos veces: en línea para escritorio y dentro de un menú para el móvil, y el menú es el mismo panel en las dos. En el móvil, el botón de menú de una sesión de escritorio remoto ocupa el sitio de [>] scripts, que ese tipo de sesión no tiene.'
Reemplaza:
  - '[[Franja de estado del terminal solo para el estado y los scripts]]'
Reemplazada por: []
Fecha de creación: 2026-11-18T20:25:00+01:00
Última modificación: 2026-11-18T20:25:00+01:00
---

# Franja de estado según la plataforma

## Contexto

[[Franja de estado del terminal solo para el estado y los scripts]] nació
cuando el usuario solo usaba la app en el móvil: casi todo acababa en la
franja y en una pantalla estrecha no cabe. Al usar el escritorio remoto desde
un portátil, el usuario vio que en escritorio la franja tiene mucho sitio
vacío y que los controles del visor quedaban bien en ella
([[Controles del visor del escritorio remoto]]). El 2026-11-18 decidió
separar la regla por plataforma.

## Decisión

- **Móvil (Android).** La franja muestra solo:
  - el estado de la conexión (la [[Barra de calidad de la conexión en la franja de estado]]
    o su marcador ASCII) y su detalle;
  - el nivel de resiliencia efectivo, en las pestañas que lo tienen;
  - `reconectar`, cuando la pestaña reconecta, está caída o ha fallado;
  - accesos que despliegan un panel: `[>] scripts` en el terminal y
    `[#] vista` en el escritorio remoto.

  Una acción nueva no se pone a la vista: entra en el panel de un botón de
  menú.
- **Escritorio (Windows y Linux).** Además de lo anterior, la franja puede
  llevar los controles de la sesión directamente, alineados a la derecha. Si
  un día no caben, se recogen en el mismo botón de menú que en el móvil, que
  despliega el mismo panel.
- Lo que la franja tiene hoy (túneles, aviso del nivel 3) se queda como está,
  igual que con la regla anterior.

## Alternativas consideradas

- Mantener la regla del móvil también en escritorio: descartada, la franja
  queda vacía y obliga a un clic más para lo que se usa a menudo.
- Poner los controles a la vista en las dos plataformas: descartada, en el
  móvil no caben.

## Consecuencias

- Positivas: en escritorio los controles quedan a un clic y aprovechan la
  franja; en el móvil la franja sigue corta.
- Negativas / compromisos: cada control nuevo tiene dos presentaciones (en
  línea y en el panel), que deben ofrecer lo mismo.
