---
Nombre: 'Alinear los keepalives de las pestañas'
Estado: 'Pendiente'
Resumen: 'Cada conexión manda su keepalive SSH cada 30 s por su cuenta, así que con varias pestañas la radio del móvil se despierta varias veces por intervalo. Se trata de alinear los keepalives de todas las conexiones (también los saltos de ProxyJump) para que salgan juntos y la radio se despierte una vez por intervalo. Es la mejora futura de ADR-0023 para el caso del servicio en primer plano.'
Decisiones: 'Creada a petición del usuario el 2026-10-14 como mejora futura de [[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]].'
Bloqueada:
  - "[[Conexiones en segundo plano según lo que pueda perder cada pestaña]]"
Fecha de creación: 2026-10-14T20:00:00+02:00
Última modificación: 2026-10-14T20:00:00+02:00
---

# Alinear los keepalives de las pestañas

## Objetivo

Reducir el consumo de batería con el servicio en primer plano de
[[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]]:
que todas las conexiones manden su keepalive a la vez, para que la radio (sobre
todo con datos móviles) se despierte una vez por intervalo en lugar de una vez
por conexión.

## Contexto

- Hoy cada `SSHClient` de sshj tiene su propio hilo de keepalive
  (`KeepAliveProvider.KEEP_ALIVE`, `keepAliveSeconds` = 30 por defecto) que
  arranca al conectar. Con N pestañas y saltos, los envíos se reparten por el
  intervalo y la radio apenas llega a apagarse.
- Una radio móvil se queda encendida varios segundos tras cada envío, así que
  agrupar los envíos ahorra más que espaciarlos.
- Hosts distintos pueden pedir intervalos distintos (`keepAliveSeconds` por
  host).

## Ideas para el diseño

- Un planificador común en `jvmShared` que mande los keepalives de todas las
  conexiones vivas en el mismo instante (p. ej. con el intervalo más corto, o
  agrupando por múltiplos), sustituyendo al proveedor de sshj.
- Mantener la detección de conexión muerta (hoy, 5 keepalives sin respuesta).
- Medir antes y después en el Pixel con `batterystats` (tiempo de radio
  activa), como en [[Medir el consumo de batería en segundo plano]].

## Criterios de finalización

- Con varias pestañas conectadas y el servicio activo, los keepalives salen
  agrupados (comprobado con una captura de tráfico o con los tiempos de envío).
- La detección de conexión caída no empeora.
- El tiempo de radio activa en el Pixel baja frente a la versión sin alinear.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
