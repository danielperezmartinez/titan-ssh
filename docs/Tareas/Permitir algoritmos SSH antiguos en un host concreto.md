---
Nombre: 'Permitir algoritmos SSH antiguos en un host concreto'
Estado: 'Planificando'
Resumen: 'Opción por host, desactivada por defecto y con un aviso visible, para conectar a equipos que solo hablan algoritmos SSH antiguos (SHA-1, cifrados CBC, firmas ssh-rsa…), como routers, NAS o servidores sin actualizar. Hoy el motor no los ofrece y la pestaña falla con un mensaje que dice qué familia falta. Aparcada hasta que el usuario tenga un equipo que lo necesite.'
Decisiones: 'El 2026-10-02 el usuario decide no hacerla todavía: sus destinos usan algoritmos modernos. Se retoma si algún equipo no conecta por este motivo.'
Bloqueada: []
Fecha de creación: 2026-10-02T15:00:00+02:00
Última modificación: 2026-10-02T15:00:00+02:00
---

# Permitir algoritmos SSH antiguos en un host concreto

## Objetivo

El motor SSH solo ofrece algoritmos actuales; la lista está en
`shared/src/jvmSharedMain/.../ssh/SshjConfig.kt`. Un servidor que no comparta
ninguno de un tipo (intercambio de claves, clave de host, cifrado o MAC) se
rechaza con `SshNoCommonAlgorithm`, y la pestaña dice qué familia falta (ver
[[SshConnector]]).

Algunos equipos solo hablan algoritmos antiguos: firmware viejo de routers,
NAS o switches, o servidores sin actualizar en muchos años. Esta tarea da una
salida controlada para conectar a uno de ellos sin bajar la seguridad del
resto.

## Criterios de finalización

- [ ] Casilla en el editor del host, desactivada por defecto, del tipo
  "Permitir algoritmos antiguos (inseguro)", con una explicación corta.
- [ ] Solo afecta a ese host (y a ese salto, si es un bastión); el resto sigue
  con la lista normal.
- [ ] Con la opción activa, los algoritmos antiguos van **después** de los
  normales, de modo que solo se usan si el servidor no tiene otros.
- [ ] Aviso visible mientras la sesión usa un algoritmo antiguo (en la franja
  de estado o en la ficha de la pestaña), con el nombre del algoritmo
  negociado.
- [ ] El mensaje de `SshNoCommonAlgorithm` sugiere activar la opción.
- [ ] Decidir qué algoritmos antiguos entran (p. ej. SHA-1 en intercambio y
  firmas, `aes-cbc`, MAC sin ETM) y cuáles nunca (DES, RC4, MD5, DH de 1024
  bits). Anotarlo en "Decisiones".
- [ ] Campo nuevo en el modelo de configuración, con su migración si hace
  falta ([[Modelo de configuración]]).

## Verificación

<Se rellena al completar. Mínimo: tests de la lista con y sin la opción,
conexión contra un sshd de pruebas limitado a algoritmos antiguos, y prueba
en el emulador.>

## Resultado

<Se rellena al completar.>
