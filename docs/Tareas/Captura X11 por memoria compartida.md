---
Nombre: 'Captura X11 por memoria compartida'
Estado: 'Pendiente'
Resumen: 'En X11 el agente lee la pantalla con GetImage por el socket de X, y el servidor X, que atiende las peticiones de una en una, deja esperando al cursor y a la inyección mientras copia la imagen (8-25 ms a 1280×800, más en pantallas grandes). Capturar con MIT-SHM (ShmGetImage) evitaría la copia por el socket y quitaría esos parones del cursor. GetImage se queda de respaldo.'
Decisiones: ''
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T19:50:00+01:00
Última modificación: 2026-10-27T19:50:00+01:00
---

# Captura X11 por memoria compartida

## Objetivo

Que en destinos X11 el cursor del escritorio remoto no tenga los parones
que aún le quedan tras [[Escritorio remoto fluido al mover el ratón]]: con
la captura y el puntero ya en conexiones distintas, el cursor llega a
~115/s, pero con huecos p99 de ~25 ms y máximos de 30-36 ms, porque el
servidor X atiende un `GetImage` entero antes que las peticiones del
puntero. Crece con el tamaño de la pantalla (1440p, 4K).

## Análisis

- [[ADR-0029 Escritorio remoto propio a través del agente]] ya prevé MIT-SHM
  como optimización con `GetImage` de respaldo: solo funciona si el
  servidor X está en la misma máquina y en el mismo espacio IPC.
  `golang.org/x/sys/unix` tiene `SysvShmGet`/`SysvShmAttach`, sin
  dependencias nuevas.
- Con `ShmGetImage` el servidor escribe la imagen en un segmento
  compartido en lugar de mandarla por el socket.
- Opcional en la misma línea: la extensión DAMAGE, para comparar solo las
  baldosas que cambiaron (X11 hoy no es un `Damager`).

## Criterios de finalización

- El capturador X11 usa MIT-SHM cuando el servidor lo ofrece y vuelve a
  `GetImage` si no.
- Medido con la sonda de la tarea anterior en `tools/test-x11`: menos
  coste por captura y colas del cursor más cortas que con `GetImage`.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
