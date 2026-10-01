---
Nombre: 'Desbloqueo del destino Windows con un servicio de sistema'
Estado: 'Planificando'
Resumen: 'Aparcada por el usuario el 2026-10-01. El mouse pad no puede escribir en la pantalla de bloqueo de Windows ni en el escritorio seguro (UAC, Ctrl+Alt+Supr): pertenecen a SYSTEM y un proceso del usuario no llega a ellos, por diseño. Para desbloquear el PC desde el móvil haría falta, como en VNC o TeamViewer, un servicio de Windows que corra como SYSTEM, instalado una vez como administrador, que se cambie al escritorio de entrada (OpenInputDesktop / SetThreadDesktop) y pueda generar Ctrl+Alt+Supr (SendSAS). Rompe el sin administrador del agente y es una superficie de escalada a SYSTEM, así que necesitaría su propia ADR y un diseño de seguridad cuidadoso.'
Decisiones: 'Sale de [[Sesión mouse pad para controlar el escritorio del destino]] y de [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]], que lo deja fuera. El usuario lo aparca el 2026-10-01: de momento no se crea ningún servicio como SYSTEM.'
Bloqueada:
  - "[[Mouse pad en destinos Windows]]"
Fecha de creación: 2026-10-01T19:45:00+02:00
Última modificación: 2026-10-01T19:45:00+02:00
---

# Desbloqueo del destino Windows con un servicio de sistema

## Objetivo

Poder iniciar sesión o desbloquear un PC Windows con el mouse pad y el teclado
de titan-ssh, y responder a los diálogos de UAC.

## Por qué no basta con el ayudante

Windows tiene escritorios separados. El ayudante de
[[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]] vive en el
escritorio del usuario. La pantalla de bloqueo, Ctrl+Alt+Supr y UAC usan el
escritorio de Winlogon, que pertenece a `SYSTEM`. Un proceso del usuario no
puede inyectar ahí, aunque sea administrador. Es una protección a propósito
para que ningún programa pueda probar contraseñas ni aceptar UAC solo. Y sin
nadie con la sesión iniciada no hay escritorio donde lanzar el ayudante.

## Idea de partida (sin decidir)

- Un servicio de Windows como `SYSTEM`, instalado una vez por un
  administrador, que sigue el escritorio de entrada
  (`OpenInputDesktop` / `SetThreadDesktop`) e inyecta allí.
- Ctrl+Alt+Supr con `SendSAS`, que exige la política que lo permite a los
  servicios.
- Antes de nada, una ADR con el modelo de amenazas: quién puede hablar con el
  servicio, cómo se autentica, y cómo se instala, actualiza y desinstala.

## Criterios de finalización

<Se definen si el usuario decide retomarla.>

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
