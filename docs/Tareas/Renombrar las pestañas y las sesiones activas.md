---
Nombre: 'Renombrar las pestañas y las sesiones activas'
Estado: 'Hecha'
Resumen: 'Petición del usuario (2026-10-13), para más adelante. Con las sesiones paralelas, las instancias de una misma sesión guardada se llaman <nombre> · 2, · 3…; el usuario quiere poder ponerles un nombre propio (por ejemplo "logs") a las pestañas y a las sesiones vivas en el destino, para reconocerlas en la lanzadera, en el panel del agente y desde otro dispositivo. Necesita un menú de acciones en la pestaña, que hoy no existe; en ese menú también encaja abrir una paralela desde la propia pestaña.'
Decisiones: 'Sale de [[Sesiones paralelas de una misma sesión guardada]] y de [[ADR-0020 Sesiones paralelas de una misma sesión guardada]], que de momento numeran las paralelas. Decidido con el usuario el 2026-10-14, junto con [[Abrir el explorador SFTP desde una sesión abierta]]: el nombre vive en el agente del destino (nivel 3), para verlo desde todos los dispositivos; en niveles 1 y 2 vive solo en la pestaña. Se renombra desde un menú de la pestaña y desde el panel del agente. El menú se abre con una pulsación larga: si se levanta el dedo queda abierto, y si se arrastra se cierra y la pestaña se mueve (en escritorio también con clic derecho). Ofrece Renombrar, Abrir en paralelo y Abrir explorador de archivos.'
Bloqueada: []
Fecha de creación: 2026-10-13T09:45:00+02:00
Última modificación: 2026-10-14T13:30:00+02:00
---

# Renombrar las pestañas y las sesiones activas

## Objetivo

Que el usuario pueda dar un nombre propio a una pestaña abierta y a la
instancia de la sesión del destino que muestra (principal o paralela), en vez
de `<nombre> · 2`, y que ese nombre se vea en la pestaña, en la lanzadera y en
el panel del agente.

## Decisiones (2026-10-14)

Tomadas con el usuario al empezarla, junto con
[[Abrir el explorador SFTP desde una sesión abierta]]:

- **Dónde vive el nombre**: en el agente del destino, para que se vea desde
  todos los dispositivos. Es una orden de control nueva del agente
  (`--rename-session`) y un campo `name` en su `--status`. En niveles 1 y 2
  no hay agente: el nombre vive solo en la pestaña (sobrevive a sus
  reconexiones, no a cerrar la app).
- **Desde dónde se renombra**: el menú de la pestaña y el panel del agente
  (junto a Terminar). La lanzadera muestra el nombre pero no lo cambia.
- **Cómo se abre el menú**: pulsación larga en la pestaña. Al cumplirse la
  pulsación se abre; si el usuario levanta el dedo, queda abierto; si en vez
  de levantarlo arrastra, el menú se cierra y la pestaña se mueve como hasta
  ahora. En escritorio también lo abre el clic derecho. Si no fuera viable,
  la alternativa a valorar con el usuario es un botón ⋮.
- **Acciones del menú**: Renombrar, Abrir en paralelo (solo si la sesión
  admite instancias, ADR-0020) y Abrir explorador de archivos (solo en
  pestañas de terminal).

## Criterios de finalización

- Se puede renombrar una pestaña o instancia abierta y el nombre se ve en la
  pestaña, la lanzadera y el panel del agente.
- El nombre sobrevive a una reconexión.
- Probado en el emulador y en escritorio.

## Verificación

Hecho el 2026-10-14 en la rama `worktree-tab-menu`, junto con
[[Abrir el explorador SFTP desde una sesión abierta]]:

- Agente: `go test` de `internal/session` y `cmd/titan-agent` en verde, con
  tests nuevos de `Rename`/`CleanName` (quita caracteres de control, corta a
  64), `--rename-session` (espacios, comillas y no ASCII llegan intactos; sin
  nombre lo quita; sin daemon no falla) y el campo `name` en
  `TestStatusJSONContract`.
- App: `:shared:desktopTest` completo, 398 tests; solo falla
  `DesktopSecretStoreTest`, que necesita el Credential Manager de Windows y no
  lo hay en la sesión 0 desde la que se ejecutó (ambiental, no toca este
  cambio). Nuevos: `SessionInstancesTest` (etiqueta con nombre propio),
  `AgentWatchTest` (la orden viaja en base64url; un agente antiguo pide
  actualizarlo) y el espejo del contrato en `AgentSessionDetailsTest`.
  Compilan `:androidApp:assembleDebug` y `:desktopApp:compileKotlin`.
- Emulador `Pixel_9_Pro_XL`, build de debug `.tab_menu` contra el contenedor
  de pruebas:
  - Pulsación larga quieta en la pestaña: abre el menú y sigue abierto al
    soltar. Pulsación larga + arrastre: el menú se cierra y la pestaña se
    reordena en el mismo gesto.
  - "Abrir en paralelo" abre `demo (agente) · 2`.
  - Renombrar a `logs` desde la pestaña: el agente lo guarda
    (`titan-session-demo-agent-p2 "logs"` en `--status`).
  - Renombrar a `logs prod` desde el panel del agente: el panel lo muestra
    (con `demo (agente) · 2` en el subtítulo), la pestaña abierta se
    actualiza sola y la lanzadera ofrece "Reengancharse a logs prod".
  - Tras forzar el cierre de la app y reengancharse, la pestaña vuelve a
    llamarse `logs prod`.
- El usuario lo probó el 2026-10-14 y dio la tarea por finalizada.

## Resultado

- Menú de la pestaña con pulsación larga (clic derecho en escritorio):
  Renombrar, Abrir en paralelo y Abrir explorador de archivos; un arrastre
  tras la pulsación cierra el menú y reordena
  ([[Menú de la pestaña con pulsación larga]]).
- El nombre se edita en una franja bajo la barra o desde el panel del agente
  (componente común `NameEditor`). En nivel 3 lo guarda el agente
  (`--rename-session <id> --name-base64 <nombre>`, campo `name` de
  `--status`) y `AgentManager` lo sincroniza con las pestañas; en niveles 1 y
  2 vive solo en la pestaña.
- La pestaña, el panel del agente y la elección de la lanzadera muestran el
  nombre en vez de `<nombre> · 2`.
