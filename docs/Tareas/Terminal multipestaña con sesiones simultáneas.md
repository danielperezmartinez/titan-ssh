---
Nombre: Terminal multipestaña con sesiones simultáneas
Estado: Pendiente
Resumen: 'Área de Sesiones: pantalla de terminal con múltiples conexiones simultáneas en pestañas, pudiendo alternar, cerrar y mover sesiones. En Android hace falta una barra de teclas accesorias sobre el teclado del sistema.'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:48:56+02:00
---

# Terminal multipestaña con sesiones simultáneas

## Objetivo

Ofrecer el área de Sesiones: una pantalla de terminal que soporte varias
conexiones SSH simultáneas mediante pestañas, lanzadas a partir de las
configuraciones guardadas, con una experiencia ágil para alternar, cerrar y mover
pestañas. Al estar en una pestaña se ve el terminal listo para trabajar.

## Criterios de finalización

- Se pueden tener varias sesiones abiertas a la vez, cada una en su pestaña.
- Alternar, cerrar y **mover/reordenar** pestañas es ágil y cómodo.
- Cada pestaña muestra su **estado**: conectando / conectado / reconectando (loader
  del nivel 1, ver [[Resiliencia de sesión ante microcortes de red]]) / caída.
- **Android:** barra de **teclas accesorias** sobre el teclado del sistema (Esc,
  Tab, Ctrl, Alt, flechas y símbolos como `|` `/` `-` `~`), ya que el teclado
  software no las trae; gestión de pegar y scroll.
- En **escritorio**, posibilidad de dividir la vista en dos terminales; en
  **Android**, una pestaña a pantalla completa.

## Contexto ya disponible (2026-09-18)

- El área de Sesiones existe como **lanzadera** (`SessionsArea`): lista las
  sesiones guardadas y muestra la conexión que cada una resuelve (config → motor),
  con un botón `[>] Lanzar` aún sin cablear. Entregada con
  [[Panel de gestión de hosts y sesiones]].
- El **nivel de resiliencia por sesión** ya se configura en el panel
  (`ResilienceLevel`, [[ADR-0003 Modelo de resiliencia por niveles]]).
- Esta tarea es la que debe **cablear el lanzamiento**: abrir la sesión sobre el
  [[Motor de conexión SSH]] en una pestaña y, en ese flujo, **ejecutar los scripts
  de inicio** ([[Scripts de inicio por sesión]], que queda bloqueada por esta para
  su parte de ejecución).

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
