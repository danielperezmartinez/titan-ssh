---
Nombre: 'Carpetas de grupos en las listas'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'Las listas de hosts y de sesiones (Configuración y lanzadera) muestran cada grupo como una carpeta plegable: fila con [#], nombre y recuento, y a la derecha [-] abierta o [+] plegada. En cada nivel van primero las carpetas, por nombre, y después lo suelto; no hay carpeta Otros, así que sin grupos la lista es la de siempre. El contenido se sangra 16dp por nivel. En Configuración los grupos se crean y se gestionan dentro de su propia lista (no hay pestaña Grupos); en la lanzadera las carpetas solo se pliegan y las vacías no se muestran.'
Decisión: 'Carpetas plegables con el vocabulario ASCII existente ([#] grupo, [+]/[-] desplegar y plegar), sangrado SpaceLg por nivel y gestión en línea con el patrón de acciones contextuales de las filas. Se quita la pestaña Grupos de Configuración.'
Consecuencias: 'El grupo se crea y se gestiona donde tiene efecto y Configuración baja a tres pestañas. El sangrado de las carpetas (16dp, sobre canvas) no se confunde con el de las acciones contextuales (24dp, sobre surface). Quien no crea grupos no ve ningún cambio en sus listas.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-01T00:50:00+02:00
Última modificación: 2026-10-01T00:50:00+02:00
---

# Carpetas de grupos en las listas

Pedida por el usuario el 2026-10-01
([[Grupos de hosts y de sesiones como carpetas]]). Modelo en
[[ADR-0015 Grupos de hosts y de sesiones independientes y anidados]]. Se
construye con [[Filas de lista con acciones contextuales desplegables]] y el
[[Vocabulario ASCII ampliado y disciplina de color]], sin glifos nuevos.

## La carpeta

- Fila `ListRow` con marcador `[#]` en `body`, el nombre del grupo y, debajo,
  lo que contiene contando sus subcarpetas: "3 sesiones", "1 host", "vacío".
- A la derecha, `[-]` si está abierta y `[+]` si está plegada, en `mute`.
- Pulsar la fila o su `[+]`/`[-]` la pliega o la despliega. Pueden estar
  abiertas varias a la vez. El estado se recuerda entre arranques y es el
  mismo en la lanzadera y en Configuración.

## Orden y sangrado

- En cada nivel van primero las carpetas, por nombre, y después lo que no tiene
  grupo, en su orden de siempre.
- **Sin carpeta "Otros"**: lo que no tiene grupo queda suelto. Sin grupos, la
  lista es exactamente la de antes.
- Cada nivel se sangra `SpaceLg` (16dp), fila y hairline incluidos, sobre
  `canvas`. Las acciones contextuales siguen con su sangrado de 24dp sobre
  `surface`, así que no se confunden.

## Configuración

- No hay pestaña Grupos. Hosts y Sesiones empiezan con `[+] Nuevo host` /
  `[+] Nueva sesión` y `[+] Nuevo grupo`. Este último se convierte en la fila
  en un campo con `[<]` y `[ok] Crear`, sin diálogos.
- El marcador o la pulsación larga de una carpeta despliegan sus acciones:
  `[+] Nuevo host/sesión en este grupo`, `[+] Nuevo subgrupo`, `[~] Renombrar`,
  `[#] Mover a otro grupo` (lista de destinos con su ruta `padre / hijo`, más
  "Fuera de carpetas") y `[x] Eliminar` en `danger`.
- Eliminar se confirma en línea y avisa de adónde va lo que contiene ("Lo que
  contiene pasa a demo" o "… fuera de carpetas").
- Las carpetas vacías se muestran, para poder gestionarlas.
- En el editor de host y de sesión, el campo "Grupo" lista los grupos de su
  ámbito con su ruta y "sin grupo". Debajo, `[+] Nuevo grupo` crea uno en el
  nivel superior sin salir del editor y lo selecciona.

## Lanzadera

- Las sesiones se muestran en las mismas carpetas, que solo se pliegan y
  despliegan. Las carpetas sin sesiones no se muestran.
