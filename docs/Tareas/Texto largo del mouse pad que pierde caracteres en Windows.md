---
Nombre: 'Texto largo del mouse pad que pierde caracteres en Windows'
Estado: 'Pendiente'
Resumen: 'Al escribir de golpe un texto largo con el mouse pad (un pegado desde el teclado del móvil), Windows no entrega todos los caracteres a la aplicación: con el Bloc de notas llegan unos 600 de 1000 en una sola trama TEXT. SendInput dice que los ha insertado todos, así que la pérdida está entre la cola de entrada y la aplicación. Espaciar los eventos casi carácter a carácter lo arregla (1998 de 2000, a unos 4 ms por carácter), pero hay que decidir el ritmo y qué pasa con el resto de la entrada mientras se escribe.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-02T19:32:17+02:00
Última modificación: 2026-10-02T19:32:17+02:00
---

# Texto largo del mouse pad que pierde caracteres en Windows

Sale de las pruebas de [[Mouse pad en destinos Windows]] del 2026-10-02.

## Objetivo

Que un texto largo que llega por el mouse pad se escriba entero en el
escritorio Windows, sin dejar la entrada bloqueada más de lo necesario.

## Qué se ha visto

En el PC de pruebas, con el ayudante de escritorio escribiendo en el Bloc de
notas (texto ASCII):

| Envío | Caracteres que llegan |
| --- | --- |
| 300 en una trama | 300 |
| 1000 en una trama | unos 560–730 |
| 2000 en una trama | unos 790–840 |
| 10 tramas de 100, cada 200 ms | 943 de 1000 |
| 2000 en lotes de 64 eventos, con 15 ms entre lotes | 968 |
| 2000 en lotes de 2 eventos, con 2 ms entre lotes | 1998 (unos 7,7 s) |

Pasaba igual con el inyector anterior, que mandaba todo el texto en una sola
llamada a `SendInput`, y con el actual, que lo manda por lotes. `SendInput`
devuelve siempre que ha insertado todos los eventos.

## Criterios de finalización

- Un pegado de 4 KiB (el máximo de una trama TEXT) llega entero al Bloc de
  notas y a otra aplicación (un navegador, por ejemplo).
- Decidido y documentado el ritmo de inyección, y si los movimientos y clics
  que llegan mientras tanto esperan o se intercalan.
- Probado en el emulador contra el sshd de Windows, y por el usuario en el
  Pixel.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
