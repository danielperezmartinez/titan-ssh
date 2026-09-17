---
Nombre: Almacenamiento seguro de credenciales
Estado: Pendiente
Resumen: Implementar la interfaz SecretStore (expect/actual) sobre el almacén nativo de cada plataforma, sin texto plano, y la autenticación por clave con clave no exportable en hardware desde la v1. Seguridad es prioridad alta.
Decisiones: Sigue [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:34:18+02:00
---

# Almacenamiento seguro de credenciales

## Objetivo

Custodiar de forma segura las credenciales SSH (claves privadas, contraseñas)
del usuario. Al tratarse de conexiones SSH la seguridad es prioridad alta: nunca
se almacenan en texto plano y se delega en los mecanismos nativos del SO, según
[[ADR-0001 Credenciales en almacén nativo del SO]]. La estrategia de
autenticación la fija [[ADR-0005 Autenticación SSH y verificación de host]].

## Criterios de finalización

- Interfaz `SecretStore` (`expect`/`actual`) en el código compartido, con
  implementaciones nativas: Android (Keystore + biometría opcional), Windows
  (DPAPI / Credential Manager) y Linux (Secret Service).
- Autenticación por clave **ed25519** preferida sobre contraseña.
- **Clave privada no exportable en hardware desde la v1**: generada y firmada en
  el almacén de claves del SO (Android Keystore / StrongBox), integrada con la
  librería SSH mediante un *signer* delegado; con fallback donde no haya soporte.
- Verificación de host key vía `known_hosts` (TOFU con confirmación).
- No existe ninguna ruta que persista credenciales en texto plano.
- (Pendiente en implementación) elegir la librería de escritorio concreta para el
  `SecretStore` (candidatas: credential-secure-storage-for-java, java-keyring).

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
