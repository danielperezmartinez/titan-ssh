---
Nombre: 'No reutilizar la contraseña como clave al instalar la clave'
Estado: 'Pendiente'
Estado desde: 2026-11-18
Resumen: 'Al pasar un host de contraseña a «Clave» en el editor, la referencia del SecretStore se queda con la de la contraseña; «Instalar la clave en el host» encuentra ahí la contraseña, la intenta leer como clave privada y falla con «No se ha podido usar la clave de este dispositivo» y «No provider available for Unknown key file». La app debe proponer una referencia nueva para la clave y, si la referencia no contiene una clave, decirlo claramente en vez de culpar al almacén.'
Decisiones: ''
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-18T20:00:00+01:00
Última modificación: 2026-11-18T20:00:00+01:00
---

# No reutilizar la contraseña como clave al instalar la clave

## Objetivo

Que cambiar un host de contraseña a clave e instalarla con «Instalar la clave
en el host» funcione a la primera, sin tener que adivinar que hay que cambiar
la referencia del SecretStore.

Encontrado por el usuario el 2026-11-18 en la app de escritorio 1.5.0 (Linux),
al editar un host que ya tenía contraseña guardada y pasarlo a «Clave»:

- `HostEditor` usa un mismo estado `secretRef` para la referencia de la
  contraseña y la de la clave privada, así que el campo «Referencia de la clave
  privada (SecretStore)» se queda con la de la contraseña.
- `SecretProvisioner.ensureSoftwareKey` reutiliza lo que haya guardado bajo
  esa referencia. Como es la contraseña, `publicLineOfPrivateKey` falla y el
  error llega al usuario como `KEY_UNAVAILABLE` («El almacén de claves del
  sistema no la ha dado…»), con el mensaje de sshj «No provider available for
  Unknown key file».
- Cambiar la referencia a un nombre nuevo (p. ej. `<host>.key`) lo resuelve.

Relacionada con [[Instalar la clave pública en el host desde la app]] y
[[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]].

## Criterios de finalización

- Al pasar de «Contraseña» a «Clave», la referencia de la clave no hereda la de
  la contraseña: se propone una nueva (p. ej. `<referencia>.key`) que no choque
  con un secreto existente.
- Si la referencia indicada ya guarda algo que no es una clave privada, el
  error lo dice tal cual (la referencia contiene otra cosa, p. ej. una
  contraseña) y propone usar otra referencia; no se presenta como fallo del
  almacén.
- La contraseña guardada no se toca ni se sobrescribe; el resumen final sigue
  ofreciendo borrarla como hasta ahora.
- Lo mismo en Android y escritorio (el editor es común).
- Test que cubra el cambio de contraseña a clave con la contraseña guardada.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Cómo probarlo

<Solo al pasar a `Por probar`: dónde, preparación, pasos, qué debe pasar y qué
enviar si falla.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
