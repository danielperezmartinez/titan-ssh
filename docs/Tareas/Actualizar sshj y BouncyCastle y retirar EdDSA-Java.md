---
Nombre: 'Actualizar sshj y BouncyCastle y retirar EdDSA-Java'
Estado: 'En curso'
Resumen: 'Las librerías criptográficas del cliente se han quedado atrás: sshj 0.39.0 (la última es 0.41.1, con RFC 8308 y preferencia por la firma RSA más fuerte), BouncyCastle 1.78.1 en bcprov y bcpkix (seis CVE públicos corregidos entre 1.79 y 1.85; la última es 1.86) y net.i2p.crypto:eddsa 0.3.0, abandonada desde 2019 y con el CVE-2020-36843 sin arreglo (maleabilidad de firmas Ed25519). Hay que actualizarlas y, si sshj lo permite, dejar de depender de EdDSA-Java.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-02T13:30:00+02:00
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

La [[Auditoría 2026-10-01 Estándar]] consultó en OSV las 181 dependencias
resueltas de la APK de release y del escritorio. Solo salen estas tres:

| Artefacto | Versión | Avisos públicos | Corregido en |
| --- | --- | --- | --- |
| `org.bouncycastle:bcprov-jdk18on` | 1.78.1 | CVE-2025-14813 (GOST), CVE-2026-0636 (LDAP), CVE-2026-8763 (Name Constraints), CVE-2026-13506 (ASN.1) | 1.85 |
| `org.bouncycastle:bcpkix-jdk18on` (transitiva) | 1.78.1 | CVE-2025-8916 (asignación excesiva), CVE-2026-5588 | 1.84 |
| `net.i2p.crypto:eddsa` | 0.3.0 | CVE-2020-36843 | sin arreglo |

Ninguno afecta de forma directa al uso que hace SSH (no hay GOST, LDAP ni
validación de certificados X.509), pero hay que estar en una versión sin
avisos.

## Criterios de finalización

- [x] sshj a la última versión estable, leyendo su changelog en busca de
  cambios de comportamiento: algoritmos por defecto, verificación de host,
  keepalive y SFTP.
  - 0.41.1 (2026-09-21). Lo que importa aquí: compilada para Java 17, sin
    EdDSA-Java, firma Ed25519 por la JCE, `server-sig-algs` (RFC 8308) para
    elegir la firma de la clave de usuario, y la preferencia de algoritmos de
    host conocidos por tipo de clave, que sigue casando con
    `hostKeyAlgorithmsFor`. Keepalive y SFTP sin cambios que nos afecten.
- [x] BouncyCastle (`bcprov`/`bcpkix`) a la última versión estable (1.86 o
  posterior; como mínimo 1.85). `bcpkix` llega como transitiva: fijarla
  también, o comprobar que la sube sshj.
  - sshj 0.41.1 trae la 1.84. Las dos se fijan a 1.86 en el catálogo y se
    declaran en `jvmSharedMain`, así que valen para Android y escritorio
    (`bcutil` sube a 1.86 con ellas).
  - Los tres jars de la 1.86 traen el mismo `META-INF/LICENSE.md` (la
    licencia MIT de Bouncy Castle), y el empaquetado de Android se queda con
    uno (`pickFirsts`).
- [x] Comprobar si la versión nueva de sshj puede hacer Ed25519 con
  BouncyCastle o con el JDK (Ed25519 nativo desde Java 15; en Android, el
  BouncyCastle completo que registra `SshAndroidCrypto`). Si puede, quitar
  `eddsa` del catálogo y sus reglas de `androidApp/proguard-rules.pro`. Si no,
  dejarlo anotado aquí con el motivo.
  - Sí: sshj ya no depende de `eddsa` y pide `Ed25519` a la JCE. Se quitan la
    entrada del catálogo, la dependencia y la regla de R8.
- [x] Revisar `SshAndroidCrypto` y [[Signer SSH delegado]]: la firma con la
  clave hardware de Android tiene que seguir funcionando.
  - `setRegisterBouncyCastle(false)` sigue dejando sshj sin proveedor fijado
    en la 0.41.1, así que no hace falta cambiar nada. Probado en el emulador.
- [x] Actualizar `THIRD_PARTY_NOTICES.md` si cambian las licencias o las
  librerías.

## Verificación

2026-10-02, en el worktree `crypto-deps`:

- `:shared:desktopTest` completo, sin fallos.
- `SshjIntegrationTest` contra `titan-test-sshd` con claves desechables
  ed25519, ECDSA P-256, RSA 3072 y ed25519 con passphrase: conexión, shell,
  `exec` y TOFU. El caso con passphrase destapó un fallo del propio test (la
  segunda conexión reutilizaba el array de la passphrase, que sshj borra al
  usarlo); se corrige en el test.
- Clave de host RSA (`rsa-sha2-512`/`256`) y ECDSA, contra un sshd temporal
  de la misma imagen: conectan.
- Classpaths de ejecución de la APK de release y del escritorio: sshj 0.41.1,
  `bcprov`/`bcpkix`/`bcutil` 1.86 y sin `net.i2p.crypto:eddsa`.
- Emulador `Pixel_9_Pro_XL`, APK de debug: contraseña con nivel 1 y túneles,
  ProxyJump con túneles, nivel 3 con el agente, y un host nuevo `hwtest` con
  clave hardware ECDSA del Keystore (el sshd registra la huella de esa
  clave).
- Emulador, APK de **release con R8** (instalada aparte con un sufijo de
  paquete temporal, sin versionar, y desinstalada después): TOFU de la clave
  ed25519 del servidor, clave hardware y nivel 3.

## Resultado

sshj 0.41.1, Bouncy Castle 1.86 y sin EdDSA-Java. Pendiente de integrar en
`main`.
