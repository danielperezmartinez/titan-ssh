---
Nombre: 'Botón a Configuración en la lanzadera vacía'
Estado: 'Hecha'
Resumen: 'Cuando no hay sesiones guardadas, la lanzadera de Sesiones muestra bajo el aviso un botón primario [*] Ir a Configuración que abre Configuración directamente en la pestaña Sesiones. Volver con [<] regresa a la lanzadera; abrir Configuración desde la cabecera sigue empezando en Hosts.'
Decisiones: 'Reutiliza [*] de [[Vocabulario ASCII ampliado y disciplina de color]] y TitanButton primario de [[Componentes UI compartidos]].'
Bloqueada: []
Fecha de creación: 2026-09-29T19:00:00+02:00
Última modificación: 2026-09-29T19:30:00+02:00
---

# Botón a Configuración en la lanzadera vacía

Petición del usuario (2026-09-29). Con la lista de sesiones vacía, la
lanzadera avisa "No hay sesiones. Créalas en Configuración → Sesiones." pero
no ofrecía forma de llegar salvo el `[*]` de la cabecera. Se añade ahí un
botón que navega a Configuración.

## Comportamiento

- Bajo el aviso, centrado, un `TitanButton` primario `[*] Ir a Configuración`
  (mismo glifo que la acción de la cabecera).
- Abre Configuración **en la pestaña Sesiones**, donde está `[+] Nueva sesión`.
- `[<]` (o el `[*]` de la cabecera) vuelve a la lanzadera.
- Abrir Configuración desde la cabecera no cambia: empieza en Hosts.

## Implementación

- `ui/SessionsArea.kt`: `SessionsArea` y `Launcher` reciben `onOpenConfig`;
  el estado vacío pasa a ser `EmptyState` + botón en una columna centrada.
- `ui/ConfigArea.kt`: nuevo parámetro `openOnSessions` que hace empezar en la
  pestaña Sesiones.
- `ui/AppShell.kt`: estado `configOnSessions`, activado por el botón y
  reiniciado al volver o al navegar desde la cabecera.

## Verificación

- `:shared:compileKotlinDesktop`, `:shared:compileAndroidMain`,
  `:desktopApp:compileKotlin` y `:androidApp:assembleDebug` →
  `BUILD SUCCESSFUL`.
- Emulador `Pixel_9_Pro_XL` (build de debug), 2026-09-29, con una
  configuración sin sesiones (luego se restauró la original): el botón aparece
  bajo el aviso; pulsarlo abre Configuración en Sesiones; `[<]` vuelve a la
  lanzadera; el `[*]` de la cabecera abre después en Hosts.
- **Confirmado por el usuario (2026-09-29)**. Cerrada.
