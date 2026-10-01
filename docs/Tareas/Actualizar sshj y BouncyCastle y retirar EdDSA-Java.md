---
Nombre: 'Actualizar sshj y BouncyCastle y retirar EdDSA-Java'
Estado: 'Pendiente'
Resumen: 'Las librerías criptográficas del cliente se han quedado atrás: sshj 0.39.0 (ya hay 0.41.x, con RFC 8308 y preferencia por la firma RSA más fuerte), BouncyCastle 1.78.1 (con CVE posteriores, aunque el que se conoce solo afecta a GOST, que SSH no usa) y net.i2p.crypto:eddsa 0.3.0, abandonada desde 2019 y con el CVE-2020-36843 sin arreglo (maleabilidad de firmas Ed25519). Hay que actualizarlas y, si sshj lo permite, dejar de depender de EdDSA-Java.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T18:11:30+02:00
---

# Actualizar sshj y BouncyCastle y retirar EdDSA-Java

## Objetivo

Que el cliente SSH use versiones mantenidas y sin CVE conocidos de sus
librerías criptográficas. Sale de la [[Auditoría 2026-10-01 Completa]]. Los
CVE son públicos y las versiones ya se ven en `gradle/libs.versions.toml`, así
que esta tarea es pública (regla 5 del [[README]]).

Contexto: el CVE-2020-36843 permite crear una segunda firma válida a partir de
una conocida. En SSH, el cliente solo verifica la firma del servidor sobre un
hash de sesión único, así que el impacto práctico es bajo. Aun así, la
librería no tiene mantenimiento y no recibirá arreglos.

## Criterios de finalización

- [ ] sshj a la última versión estable, leyendo su changelog en busca de
  cambios de comportamiento: algoritmos por defecto, verificación de host,
  keepalive y SFTP.
- [ ] BouncyCastle (`bcprov`/`bcpkix`) a la última versión estable.
- [ ] Comprobar si la versión nueva de sshj puede hacer Ed25519 con
  BouncyCastle o con el JDK (Ed25519 nativo desde Java 15; en Android, el
  BouncyCastle completo que registra `SshAndroidCrypto`). Si puede, quitar
  `eddsa` del catálogo y sus reglas de `androidApp/proguard-rules.pro`. Si no,
  dejarlo anotado aquí con el motivo.
- [ ] Revisar `SshAndroidCrypto` y [[Signer SSH delegado]]: la firma con la
  clave hardware de Android tiene que seguir funcionando.
- [ ] Actualizar `THIRD_PARTY_NOTICES.md` si cambian las licencias o las
  librerías.

## Verificación

<Se rellena al completar. Mínimo:
- tests de escritorio;
- test SSH opcional contra el servidor de pruebas con claves ed25519, ECDSA y
  RSA, y con contraseña;
- APK de release (R8) en el emulador: clave hardware, nivel 3 y túneles.>

## Resultado

<Se rellena al completar.>
