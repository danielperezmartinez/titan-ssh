---
Nombre: 'Escritorio remoto en destinos Linux X11'
Estado: 'En curso'
Resumen: 'Subtarea 1 del escritorio remoto: ver y controlar un escritorio Linux con X11, y construir la base común (tipo de sesión DESKTOP, tramas de pantalla del protocolo, modo titan-agent --screen, decodificación y vista en la app de escritorio y en Android). El agente habla con el servidor X del usuario con un cliente X11 propio y mínimo (GetImage, XFIXES para el cursor, XTEST para la entrada), sin ayudante. Se prueba con un contenedor desechable con Xvfb y sshd.'
Decisiones: 'Parte de [[Escritorio remoto a través del agente]]. Diseño en [[ADR-0029 Escritorio remoto propio a través del agente]].'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T10:50:00+02:00
Última modificación: 2026-10-21T10:50:00+02:00
---

# Escritorio remoto en destinos Linux X11

Parte de [[Escritorio remoto a través del agente]].

## Objetivo

Que una sesión de escritorio remoto enseñe y controle un escritorio Linux que
corre sobre X11, y dejar hecha la base que reutilizan Windows y Wayland.

## Plan

1. **Agente**: cliente X11 mínimo (conexión por socket Unix, cookie
   `MIT-MAGIC-COOKIE-1` de `XAUTHORITY` o `~/.Xauthority`, `GetImage`,
   XFIXES `GetCursorImage`, XTEST `FakeInput`, mapa de teclado); búsqueda del
   `DISPLAY` del usuario en `/proc`; codificador de baldosas
   (`FILL`/`ZLIB`/`JPEG`) con control de flujo por acuses; modo `--screen`.
2. **Protocolo**: tramas nuevas en `protocol.go` y `AgentProtocol.kt`, con
   tests a los dos lados.
3. **App**: `SessionType.DESKTOP`, `ScreenTransport` (como `InputTransport`),
   decodificador de baldosas y búfer de imagen, vista con el cursor remoto,
   entrada por ratón y teclado en escritorio y por gestos en Android.
4. **Pruebas**: contenedor con Xvfb y sshd (`tools/test-x11/`), tests de Go
   contra Xvfb y un test de integración opcional de Kotlin que recibe y
   decodifica fotogramas reales.

## Criterios de finalización

- Contra un escritorio X11: se ve la pantalla, se actualiza al cambiar, el
  cursor se ve y sigue al ratón, y funcionan mover, clic izquierdo y derecho,
  arrastrar, scroll, texto con acentos y ñ y combinaciones de teclas.
- Un microcorte congela la imagen y la reconexión trae un fotograma entero sin
  intervención del usuario.
- Un cambio de resolución del destino se refleja en la app.
- Sin sesión X11, error claro (`E_NO_DESKTOP`).
- Tests de Go del cliente X11 y del codificador; test de integración de
  Kotlin contra el contenedor.
- Probado en el emulador de Android y en la app de escritorio, y confirmado
  por el usuario en un escritorio X11 real.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
