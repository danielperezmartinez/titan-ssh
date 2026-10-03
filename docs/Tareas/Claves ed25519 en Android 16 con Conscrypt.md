---
Nombre: 'Claves ed25519 en Android 16 con Conscrypt'
Estado: 'Hecha'
Resumen: 'Regresión de v0.1.0-beta.11: en Android 16 o superior, ninguna conexión a un host con clave de host ed25519 funciona; la franja dice "Could not connect" y el detalle "Don''t know how to encode key: …OpenSslEdDsaPublicKey". Al retirar EdDSA-Java, sshj crea las claves Ed25519 con la JCA, y en Android 16 responde primero Conscrypt, cuyas claves se identifican con el OID 1.3.101.112 en vez de "Ed25519", así que sshj no sabe de qué tipo son. Arreglo: un proveedor JCE propio, en la primera posición, con solo la fábrica de claves Ed25519 de BouncyCastle.'
Decisiones: 'Arreglo acotado a la fábrica de claves Ed25519, sin forzar BouncyCastle para todo, porque forzarlo rompe la firma con la clave hardware (ver [[ADR-0005 Autenticación SSH y verificación de host]]). Viene de [[Actualizar sshj y BouncyCastle y retirar EdDSA-Java]].'
Bloqueada: []
Fecha de creación: 2026-10-03T09:30:00+02:00
Última modificación: 2026-10-03T18:06:10+02:00
---

# Claves ed25519 en Android 16 con Conscrypt

## Objetivo

Que el cliente Android vuelva a conectar con hosts que presentan una clave de
host ed25519 (la de casi cualquier OpenSSH moderno) en Android 16 o superior.

## Diagnóstico

- Lo vio el usuario el 2026-10-03 en el Pixel de pruebas con v0.1.0-beta.11,
  contra el destino Windows, gracias al panel de
  [[Detalle del fallo de conexión en la franja de la pestaña]]:
  `TransportException` → `SSHException` → `UnsupportedOperationException:
  Don't know how to encode key: com.android.org.conscrypt.OpenSslEdDsaPublicKey`.
- sshj 0.41.1 crea las claves Ed25519 con `KeyFactory.getInstance("Ed25519")`
  y las reconoce por `getAlgorithm()` (`Ed25519` o `EdDSA`). Sin proveedor
  forzado (necesario para la clave hardware), la JCA elige el primero de la
  lista. En Android 16 es Conscrypt, y su `OpenSslEdDsaPublicKey` devuelve
  `1.3.101.112`.
- El emulador `Pixel_9_Pro_XL` es Android 15: su Conscrypt no trae Ed25519, la
  clave sale de BouncyCastle y todo funciona. Por eso no se vio antes de
  publicar.

## Criterios de finalización

- [x] `Ed25519KeysProvider` (en `jvmSharedMain`) con solo `KeyFactory.Ed25519`
  de BouncyCastle, instalado por `SshAndroidCrypto` en la primera posición.
- [x] Test de escritorio que imita a Conscrypt y comprueba que, con el
  proveedor, sshj reconoce y codifica la clave.
- [x] Sin regresiones en el emulador (Android 15): contraseña y clave hardware.
- [x] Comprobado en un Android 16 o superior.

## Verificación

- `:shared:compileAndroidMain`, `:shared:desktopTest` y
  `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`. `Ed25519KeysProviderTest`:
  sin el proveedor la clave sale `unknown`; con él, `ssh-ed25519` y se codifica.
- Emulador `Pixel_9_Pro_XL` (Android 15, build de debug), 2026-10-03:
  "proyecto demo" (contraseña, contenedor de pruebas) conecta a nivel 1 y
  "Apps (agente)" (clave hardware, destino Windows) a nivel 3 con el agente.
- Publicado en v0.1.0-beta.13. El usuario instaló la APK del Release en el
  Pixel de pruebas (Android 16+) el 2026-10-03: conecta con el destino Windows
  y desde ella actualizó el agente.

## Resultado

- `Ed25519KeysProvider` en `jvmSharedMain`, instalado por `SshAndroidCrypto`
  (paso 3 de su KDoc). Publicado en v0.1.0-beta.13.
