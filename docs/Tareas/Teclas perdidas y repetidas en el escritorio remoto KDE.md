---
Nombre: 'Teclas perdidas y repetidas en el escritorio remoto KDE'
Estado: 'Por probar'
Estado desde: 2026-11-19
Resumen: 'Controlando por escritorio remoto un destino KDE Plasma Wayland, al escribir deprisa se perdían o desordenaban letras, y a veces la última tecla se quedaba repitiéndose hasta pulsar otra. Causa medida en local sin red: el agente mandaba cada pulsar y soltar al portal RemoteDesktop como llamada D-Bus sin respuesta, y xdg-desktop-portal las atiende en varios hilos y las reenvía desordenadas a KWin (un soltar que adelanta a su pulsar deja la tecla pulsada y autorrepitiendo). Arreglo: cada llamada Notify espera su respuesta antes de la siguiente. La sospecha de consumo de memoria del agente se midió y se descartó.'
Decisiones: 'Parte de [[Escritorio remoto en destinos Linux Wayland]] (entrada por las llamadas Notify del portal, [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]). El 2026-11-19 el usuario pide trabajar directamente en main. Solo cambia la sesión del portal; la vía de Mutter (GNOME desatendido) habla directamente con Mutter, que atiende en un solo hilo, y sigue con llamadas sin respuesta.'
Bloqueada: []
Prueba: 'Escribir deprisa en Konsole y KWrite de un KDE Wayland por escritorio remoto: ni letras perdidas, ni desordenadas, ni teclas que se repiten solas'
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-19T21:45:00+01:00
Última modificación: 2026-11-19T21:45:00+01:00
---

# Teclas perdidas y repetidas en el escritorio remoto KDE

Parte de [[Escritorio remoto en destinos Linux Wayland]].

## Objetivo

El usuario controla por escritorio remoto, desde la app de escritorio, un
destino KDE Plasma Wayland. Vio tres problemas:

1. A veces la última tecla pulsada se queda enviándose sin fin hasta pulsar
   otra.
2. Al escribir rápido se pierden letras.
3. Sospecha que el agente consume mucha RAM: tuvo que cerrar la conexión porque
   el destino se quedaba sin memoria.

## Diagnóstico

**Memoria (descartada).** La sesión SSH de esa conexión, con el agente de
captura dentro, tuvo un pico de 102 MB. Medido en el destino con la sonda
`titan-screenprobe` durante 2 minutos (1.299 fotogramas a 1920×1080): el
proceso de captura se queda en ~84 MB sin crecer y KWin sube ~20 MB mientras
captura y los libera al cerrar. La memoria se la comían otros procesos del
destino ajenos a titan-ssh.

**Teclado.** El visor nunca deja una tecla pulsada: cada letra viaja como
`TEXT` o como `KEY` con `ActionPress`, y el agente la convierte en pulsar y
soltar seguidos. Reproducido en el propio destino, sin red, con la sonda
escribiendo un texto conocido en KWrite por el agente: letras perdidas y
desordenadas («QUIKCB», «NOXWRFO», «fin» → «i f» + «5n»).

Causa: `portal.Session.notify` mandaba las llamadas `Notify*` del portal
RemoteDesktop como mensajes D-Bus sin respuesta (`NO_REPLY_EXPECTED`).
xdg-desktop-portal atiende las llamadas en un conjunto de hilos, así que las
reenvía al backend de KDE en otro orden. Si el soltar de una tecla adelanta a
su pulsar, la letra se pierde o se queda pulsada, y la app la autorrepite
hasta que llega otra pulsación.

Con una copia del agente que espera la respuesta de cada llamada, el mismo
texto sale exacto. Lo único que se perdía eran las primeras letras de una
sesión del portal recién abierta, y un relleno inicial lo absorbe. Eso no
ocurre en el uso normal, porque nadie escribe en el primer segundo.

## Criterios de finalización

- Cada llamada `Notify*` de la sesión del portal espera su respuesta, con un
  tiempo máximo, antes de mandar la siguiente.
- Escribir deprisa por escritorio remoto en un KDE Wayland no pierde, no
  desordena ni repite teclas.

## Verificación

- `go vet` y `go test ./...` del agente en verde.
- Ráfaga de 5 líneas conocidas (mayúsculas, dobles letras, dígitos) tecleada
  por el agente con la sonda en KWrite de un KDE Plasma Wayland real: con el
  cambio sale exacta; sin él, desordenada.

## Cómo probarlo

- **Dónde:** la canary que incluya el commit «Keep the order of remote desktop
  input on the portal», en la app que conecta (cualquiera) y, sobre todo, en
  el agente canary del destino, que se actualiza al conectar.
- **Preparación:** un destino KDE Plasma Wayland y una sesión «escritorio» a él
  desde la canary.
- **Pasos:**
  1. Abrir Konsole en el destino y escribir muy deprisa varias frases, con
     mayúsculas, dobles letras (`mississippi bookkeeper`) y números.
  2. Repetirlo en KWrite o en el navegador.
  3. Mantener pulsada una tecla, soltarla y comprobar que deja de repetirse al
     soltar.
  4. Mover el ratón y arrastrar ventanas para comprobar que el puntero sigue
     igual de fluido.
- **Qué debe pasar:** el texto sale exacto y en orden, ninguna tecla se queda
  repitiéndose y el puntero no va peor que antes.
- **Si falla:** la frase que se escribió y lo que salió, la versión del agente
  canary (`~/.local/share/titan-ssh-canary/`) y si el fallo es de letras o de
  puntero.

## Resultado

`agent/internal/portal/portal.go`: `notify` usa `Conn.Call` con un tiempo
máximo de 2 s (`notifyTimeout`) en lugar de `Conn.Send`, así que las llamadas
de entrada al portal llegan en orden.
