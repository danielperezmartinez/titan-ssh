---
Nombre: 'Sin rastro de la automatización en destinos Windows'
Estado: 'Pendiente'
Resumen: 'En un destino Windows, la automatización de la sesión sigue dejando rastro en la terminal, y a veces la estropea. Son tres puntos que el usuario vio el 2026-09-30 en el Pixel, por nivel 3. (1) Una pestaña que se engancha a una sesión del agente que ya existía no comprueba la shell del destino, la trata como POSIX y borra filas en una consola ConPTY: quedan trozos del centinela y se pierde el prompt. (2) El último script escribe un centinela aunque detrás no haya nada que esperar; si ese script lanza otra shell (pwsh), el centinela de cmd.exe lo lee PowerShell, sale tal cual y el script caduca a los 30 s. (3) En Windows el centinela no se borra; se probará a vaciar sus filas en su sitio en vez de quitarlas.'
Decisiones: 'Sale de [[Terminal fluida con ajuste de líneas y sin rastro de la automatización]] al cerrarla (2026-09-30), por petición del usuario. Que la misma sesión guardada en dos pestañas comparta el PTY es lo decidido en [[Transparencia y control del agente en el destino]] y no cambia. Continúa [[Ruta inicial y scripts de inicio en destinos Windows]].'
Bloqueada: []
Fecha de creación: 2026-09-30T21:00:00+02:00
Última modificación: 2026-09-30T21:00:00+02:00
---

# Sin rastro de la automatización en destinos Windows

## Objetivo

Que en un destino Windows (`cmd.exe` o PowerShell) la automatización de la
sesión (la ruta inicial, los scripts de inicio y sus centinelas) no estropee
nunca la terminal y, si se puede, no deje rastro. Lo que vio el usuario está en
**Prueba del usuario** de
[[Terminal fluida con ajuste de líneas y sin rastro de la automatización]].

## Puntos

### 1. Reenganche sin detectar la shell (fallo)

- `SessionTab.concealAutomation` solo se salta los destinos que no son POSIX
  cuando `remoteShell` ya se conoce. `remoteShell` lo rellena `detectShell`,
  y a `detectShell` solo lo llama la automatización cuando la necesita.
- Una pestaña que se engancha a una sesión del agente que ya existía
  (`onAttached(fresh = false)`) no lanza la automatización. `remoteShell` se
  queda en `null`, se borran filas como si fuera POSIX y la consola ConPTY
  se descuadra.
- Pasa con la misma sesión guardada abierta en dos pestañas y al reabrir una
  sesión tras reiniciar la app.
- Arreglo previsto: no borrar nada mientras la shell no se conozca, y
  detectarla también al reengancharse, porque el reenganche reproduce centinelas
  de la ejecución anterior.

### 2. Centinela del último script

- El centinela solo sirve para que el siguiente comando espere al anterior.
  La ruta inicial sin scripts ya no lo escribe (`ScriptRunner`: "alone, the
  sentinel would just print noise").
- Propuesta: tampoco lo escribe el último paso. Resuelve el caso de un último
  script que lanza otra shell (`pwsh`), cuyo centinela de `cmd.exe` imprime
  `…:%errorlevel%:…` literal y acaba en `TIMED_OUT`.
- A valorar: qué hacer si un script que cambia de shell no es el último. Por
  ejemplo, documentarlo en el editor de scripts, o que el paso siguiente espere
  un patrón en lugar del centinela.
- Hay que comprobar qué se pierde: el estado de salida del último script
  quedaría como `SENT`. Mirar quién lee los `ScriptOutcome`.

### 3. Centinela visible en Windows

- Hoy no se borra, a propósito: ConPTY repinta por posición absoluta y quitar
  filas descuadra la pantalla.
- Probar a vaciar las filas del centinela en su sitio, sin moverlas, contra
  el sshd de Windows del PC de desarrollo. Hay que ver qué pasa cuando ConPTY
  repinta: al redimensionar, al hacer scroll y con `cls`.
- Si no se sostiene, se deja visible en Windows y se anota el motivo.

## Criterios de finalización

- Una pestaña que se engancha a una sesión viva de un destino Windows muestra
  la terminal intacta, sin trozos de centinela ni líneas perdidas.
- Un último script que lanza `pwsh` no deja un centinela literal ni caduca.
- Punto 3 resuelto o descartado con el motivo anotado.
- Tests del emulador y de `ScriptRunner` para cada caso. Prueba en el emulador
  contra el sshd de Windows y contra el contenedor de pruebas, que confirma que
  en POSIX no se ha roto nada.

## Verificación

## Resultado
