---
Nombre: Panel de gestión de hosts y sesiones
Estado: Pendiente
Resumen: 'Área de Configuración: gestionar y persistir hosts (reutilizables) y sesiones que los referencian, con modelo propio (sin imitar Termius). El host define "dónde y cómo conectar"; la sesión "qué hacer al conectar".'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:48:56+02:00
---

# Panel de gestión de hosts y sesiones

## Objetivo

Permitir al usuario crear, guardar y organizar hosts y sesiones SSH desde un
panel propio. El modelo de gestión de configuraciones no debe replicar el de
Termius (aspecto que no convence al usuario); se busca un enfoque propio.

## Criterios de finalización

- **Hosts**: crear, editar, eliminar y persistir hosts reutilizables (hostname/IP,
  puerto, usuario por defecto, método de auth, host key/known_hosts, keepalive,
  jump host/ProxyJump, alias, color/icono).
- **Sesiones**: crear sesiones que **reutilizan un host** almacenado y añaden lo
  suyo (scripts vía [[Scripts de inicio por sesión]], directorio inicial, nivel de
  resiliencia según [[ADR-0003 Modelo de resiliencia por niveles]], túneles/port
  forwarding, apariencia). Pueden **sobreescribir** valores por defecto del host.
- **Organización**: agrupar hosts y sesiones (carpetas/etiquetas por proyecto).
- La configuración persiste entre arranques; las credenciales se custodian según
  [[ADR-0001 Credenciales en almacén nativo del SO]].

## Elementos del área de Configuración (alcance v1, confirmado)

- **Snippets / comandos rápidos** reutilizables (biblioteca global insertable en
  cualquier sesión), como **tercer elemento de primera clase** junto a hosts y
  sesiones. Son también los scripts "bajo demanda" (ver
  [[Scripts de inicio por sesión]]).
- **Túneles / port forwarding** por sesión (local, remoto, SOCKS dinámico) y
  **jump host (ProxyJump)** en el host.
- **Nivel de resiliencia por sesión** (base / auto-tmux / agente, ver
  [[ADR-0003 Modelo de resiliencia por niveles]]).
- **Agrupación por proyecto** (carpetas/etiquetas) para hosts y sesiones.
- **Apariencia del terminal** (fuente, tamaño, tema de color) global con override
  por sesión.
- **Plantillas / duplicar sesión** para crear una sesión a partir de otra.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
