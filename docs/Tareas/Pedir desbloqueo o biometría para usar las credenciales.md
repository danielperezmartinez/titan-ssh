---
Nombre: 'Pedir desbloqueo o biometría para usar las credenciales'
Estado: 'Planificando'
Resumen: 'Opción para que usar una credencial (la clave hardware, o los secretos del almacén en Android) exija autenticar al usuario con biometría o con el desbloqueo del dispositivo. Hoy quien tenga el móvil desbloqueado puede conectar a todos los hosts. ADR-0001 y ADR-0005 ya preveían la biometría opcional. De paso, reducir las copias de contraseñas que quedan en memoria como String inmutable.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T18:11:30+02:00
---

# Pedir desbloqueo o biometría para usar las credenciales

## Objetivo

Proteger las credenciales frente a quien tenga el dispositivo ya desbloqueado.
Sale de la [[Auditoría 2026-10-01 Completa]] como mejora de endurecimiento, no
como fallo. Es una decisión de producto, y por eso empieza en `Planificando`.
Lo previeron [[ADR-0001 Credenciales en almacén nativo del SO]]
("biometría opcional") y [[ADR-0005 Autenticación SSH y verificación de host]].

## Preguntas abiertas para el usuario

- ¿Por host, o un ajuste global?
- ¿Cada conexión, o una ventana de tiempo tras autenticarse
  (`setUserAuthenticationParameters` con un timeout)? Las reconexiones
  automáticas tras un microcorte no deberían pedirla otra vez, o el pilar de
  resiliencia se resiente.
- ¿En escritorio? El almacén del SO ya va ligado a la sesión del usuario; se
  puede dejar fuera del alcance.

## Criterios de finalización

- [ ] Decisión del usuario sobre las preguntas anteriores, anotada en
  `Decisiones`.
- [ ] Android: las claves hardware nuevas que lo pidan se generan con
  `setUserAuthenticationRequired(true)`, y su uso pasa por `BiometricPrompt`.
  Lo mismo para la clave maestra del `SecretStore`, si se decide (exige migrar
  los secretos ya cifrados).
- [ ] Higiene de memoria: evitar el paso de la contraseña a `String` en
  `SshjConnector` y en `CredentialResolver`, en la medida en que sshj lo
  permita (`PasswordFinder` con `char[]`).

## Verificación

<Se rellena al completar: en el emulador con huella configurada.>

## Resultado

<Se rellena al completar.>
