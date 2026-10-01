---
Nombre: 'Mouse pad en destinos Linux X11'
Estado: 'Planificando'
Resumen: 'Subtarea 2 del mouse pad: controlar ratón y teclado de un escritorio Linux con X11. No hace falta un ayudante en otra sesión: el agente se conecta al servidor X del usuario si encuentra su DISPLAY y su cookie (XAUTHORITY), e inyecta con la extensión XTEST. Queda por decidir si se escribe un cliente X11 mínimo propio en Go (la única dependencia del agente es golang.org/x/sys) o se usa una librería con su ADR. El texto fuera de la distribución del teclado se escribe reasignando temporalmente una tecla libre, como hace xdotool. Se aborda después de Windows y solo si el usuario lo pide.'
Decisiones: 'Parte de [[Sesión mouse pad para controlar el escritorio del destino]]. Reutiliza la base común de [[Mouse pad en destinos Windows]].'
Bloqueada:
  - "[[Mouse pad en destinos Windows]]"
Fecha de creación: 2026-10-01T18:25:00+02:00
Última modificación: 2026-10-01T18:25:00+02:00
---

# Mouse pad en destinos Linux X11

Parte de [[Sesión mouse pad para controlar el escritorio del destino]].
Reutiliza el tipo de sesión, el protocolo de entrada y la pestaña de gestos
de [[Mouse pad en destinos Windows]].

## Objetivo

Que una sesión mouse pad controle el ratón y el teclado de un escritorio Linux
que corre sobre X11.

## Propuesta

1. **Encontrar la sesión gráfica**: una sesión SSH no trae `DISPLAY`. Se
   busca la sesión gráfica del usuario (`loginctl` con `Type=x11` y su
   `Display`, o el entorno de un proceso del escritorio en `/proc`) y su
   cookie (`XAUTHORITY` o `~/.Xauthority`). Si la sesión es Wayland, se pasa a
   [[Mouse pad en destinos Linux Wayland]]: XWayland no sirve para controlar
   el escritorio.
2. **Inyección con XTEST** (`FakeInput`): movimiento relativo, botones y
   rueda (botones 4 a 7). Dos opciones, a decidir:
   - un cliente X11 mínimo en Go (conexión, autenticación
     `MIT-MAGIC-COOKIE-1`, `QueryExtension`, XTEST y el mapa de teclado),
     sin dependencias nuevas;
   - una librería X11 en Go, que necesita su ADR.
3. **Teclado**: X11 trabaja con códigos de tecla que dependen de la
   distribución. Las teclas se traducen por keysym con el mapa del servidor,
   y un carácter que no esté en la distribución se escribe reasignando un
   código libre durante la pulsación y restaurándolo después.
4. **Sin ayudante**: el daemon puede conectarse al servidor X por el socket
   local con la cookie, así que no hace falta un proceso en otra sesión.

## Criterios de finalización

- Contra un escritorio X11: mover, clic izquierdo y derecho, arrastrar, scroll
  vertical y horizontal, texto con acentos y ñ, teclas especiales y
  combinaciones.
- Sin sesión X11 iniciada, o con una sesión Wayland, error claro.
- Tests del cliente X11 contra un servidor virtual (`Xvfb` en un contenedor).
- Probado en el emulador y confirmado por el usuario en un escritorio X11 real.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
