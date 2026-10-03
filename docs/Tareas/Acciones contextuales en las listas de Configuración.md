---
Nombre: 'Acciones contextuales en las listas de Configuración'
Estado: 'En curso'
Resumen: 'Las listas de Hosts, Sesiones y Scripts de Configuración despliegan las mismas acciones que la lanzadera (marcador o pulsación larga): Editar, Duplicar, Ver el agente del destino cuando aplica, y Eliminar con confirmación en la fila. Las acciones salen de un componente común, EntryActions, que también usa la lanzadera. Pulsar la fila sigue abriendo el editor.'
Decisiones: 'Pedida por el usuario el 2026-10-03. Sigue [[Filas de lista con acciones contextuales desplegables]]. Duplicar un host conserva sus referencias de secretos (las dos copias usan las mismas credenciales guardadas); duplicar un script de la biblioteca no mueve a las sesiones que usan el original. Los avisos de Eliminar son los mismos que en los editores. Terminar en el destino sigue solo en la lanzadera, que es la que lee el estado del agente.'
Bloqueada: []
Fecha de creación: 2026-10-03T09:20:00+02:00
Última modificación: 2026-10-03T09:20:00+02:00
---

# Acciones contextuales en las listas de Configuración

## Objetivo

Petición del usuario (2026-10-03): en la lanzadera cada sesión despliega sus
acciones (Editar, Duplicar, Eliminar…), pero las listas de Configuración no
las tenían en ninguna pestaña. Se consolida: las tres listas las ofrecen igual
que la lanzadera. Continúa [[Acciones contextuales en las filas de lista]],
que solo las puso en la lanzadera.

## Comportamiento

- En Hosts, Sesiones y Scripts: pulsar la fila abre su editor (como antes);
  el marcador o mantener pulsada (clic derecho en escritorio) despliega o
  pliega las acciones. Una sola fila abierta por lista, carpetas incluidas.
- Acciones, en este orden:
  - `[~] Editar`.
  - `[+] Duplicar`: crea "(copia)" en el mismo grupo y pliega la fila.
  - `[@] Ver el agente del destino`: en un host, el agente de su usuario por
    defecto; en una sesión de nivel 3 que resuelve, el de su destino. No en
    los scripts.
  - `[x] Eliminar`: pide confirmación en la fila, con el mismo aviso que el
    editor (sesiones que se quedan sin host, si la sesión sigue viva en el
    destino, cuántas sesiones usan el script).

## Implementación

- `ConfigController`: `duplicateHost` (id nuevo, mismo `auth`, "(copia)" sobre
  el alias o, si está vacío, el hostname) y `duplicateLibraryScript`.
- `ui/Components.kt`: `EntryActions`, las acciones comunes de una entrada
  (Editar, Duplicar, extras, Eliminar con `ConfirmRow`). La lanzadera pasa a
  usarlo; Ver el agente y Terminar en el destino van como extras.
- `ui/ConfigArea.kt`: `GroupedConfigList` entrega a cada fila un `EntryRow`
  (desplegada, abrir/cerrar, confirmando borrado); `HostList`, `SessionList`
  y `LibraryScriptList` montan sus acciones con `EntryActions`.
- `hostDeleteWarning` (`HostEditor.kt`) y `libraryScriptDeleteWarning`
  (`LibraryScriptEditor.kt`): el aviso de borrado, compartido entre editor y
  lista.

## Criterios de finalización

- Las tres listas de Configuración despliegan sus acciones y cada una funciona.
- La lanzadera se comporta igual que antes.

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:desktopApp:compileKotlin`, `:shared:desktopTest` (con tests nuevos de
  `duplicateHost` y `duplicateLibraryScript`) y `:androidApp:assembleDebug` →
  `BUILD SUCCESSFUL` (2026-10-03).
- Emulador `Pixel_9_Pro_XL`, 2026-10-03: la pulsación larga sobre un host
  desplegó Editar, Duplicar, Ver el agente del destino y Eliminar. La prueba se
  cortó porque otra sesión instaló otra build en el emulador; **falta** probar
  Duplicar y Eliminar en las tres listas, y la lanzadera.

## Resultado

Pendiente de la verificación en el emulador.
