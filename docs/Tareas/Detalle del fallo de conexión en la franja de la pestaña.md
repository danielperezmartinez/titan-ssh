---
Nombre: 'Detalle del fallo de conexión en la franja de la pestaña'
Estado: 'En curso'
Resumen: 'Cuando una pestaña no conecta, la franja de estado solo dice el mensaje corto (por ejemplo "Could not connect to <host>:22"), que cubre cualquier fallo antes de la autenticación y esconde la causa real (timeout, conexión rechazada, sin ruta, error del intercambio de claves). La franja se queda como está, y al pulsar su texto se despliega debajo un panel con la causa técnica, como se despliegan los scripts y los túneles.'
Decisiones: 'Pedida por el usuario el 2026-10-03, al no poder conectar el Pixel a un destino Windows con v0.1.0-beta.11 sin más pista que "Could not connect". El usuario no quiere el detalle en la propia franja: solo al pulsarla, en un panel desplegable como el de scripts.'
Bloqueada: []
Fecha de creación: 2026-10-03T08:35:00+02:00
Última modificación: 2026-10-03T08:47:00+02:00
---

# Detalle del fallo de conexión en la franja de la pestaña

## Objetivo

Que quien no consigue conectar pueda ver **por qué** sin herramientas de
desarrollo, y copiarlo para avisar del fallo. Hoy el mensaje de la franja es
el de la excepción de más arriba; la causa (la excepción de red o del
protocolo que hay debajo) se pierde.

## Comportamiento acordado

- La franja muestra lo mismo que hoy.
- Si hay una causa técnica, pulsar el texto de estado despliega debajo un
  panel con ella; pulsarlo otra vez lo pliega. Abrirlo cierra los paneles de
  scripts y túneles, y al revés.
- El panel lista la cadena de causas (`Tipo: mensaje`, una por línea), y el
  texto se puede seleccionar para copiarlo.
- Aplica a la pestaña de terminal y a la de mouse pad.

## Criterios de finalización

- [x] Fallo al conectar, fallo de autenticación y desconexión tras agotar los
  reintentos llevan su causa al panel.
- [x] Panel desplegable en la franja de la terminal y del mouse pad.
- [x] Tests de la cadena de causas.
- [x] Probado en el emulador `Pixel_9_Pro_XL` con un destino que no responde.

## Verificación

- `:shared:compileAndroidMain`, `:shared:desktopTest` y
  `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`. Tests nuevos:
  `FailureCauseTest` (cadena, sin causa, `includeSelf`, repetidas y ciclos) y
  tres en `SessionTabReconnectTest` (primer fallo, al rendirse, y que una
  conexión recuperada olvida la causa anterior).
- Emulador `Pixel_9_Pro_XL` (build de debug), 2026-10-03, con el servidor de
  pruebas apagado: la franja dice "Could not connect to …:2222" como antes; al
  pulsarla se despliega `ConnectException: failed to connect to … ECONNREFUSED
  (Connection refused)` y su `ErrnoException`. Se pliega al pulsar otra vez, y
  abrir los túneles la cierra.
- La franja del mouse pad solo se ha compilado: no se ha provocado un fallo en
  una sesión mouse pad.

## Resultado

- `TabStatus` gana `cause`, que `SessionTab` rellena con `failureCause()`:
  fallo al conectar, de autenticación, de algoritmos y de credenciales, y
  mientras reconecta o al rendirse, el último fallo y su causa.
- `StatusDetailPanel` en `ui/Components.kt` (ver
  [[Componentes UI compartidos]]): la franja de la terminal y la del mouse pad
  lo despliegan al pulsar su texto, que se pone en `accent` mientras está
  abierto.
