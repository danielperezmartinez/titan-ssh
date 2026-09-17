---
Nombre: Autenticación SSH y verificación de host
Número: 5
Estado: Aceptada
Resumen: 'Autenticación preferente por clave (ed25519) sobre contraseña, con verificación de host key (known_hosts, TOFU). Objetivo desde la v1: clave privada no exportable generada y firmada en hardware (Android Keystore / StrongBox), delegando la firma con un signer propio en la librería SSH.'
Decisión: Preferir clave ed25519 sobre contraseña, verificar host keys vía known_hosts, y usar desde la v1 una clave no exportable respaldada por hardware que firma sin exponerse.
Consecuencias: Requiere integrar un signer personalizado con sshj/MINA que delega la firma en el almacén de claves del SO; la clave privada nunca sale del hardware.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T16:34:18+02:00
Última modificación: 2026-09-17T16:34:18+02:00
---

# ADR-0005 · Autenticación SSH y verificación de host

## Contexto

La seguridad es prioridad alta. La custodia de secretos en reposo la fija la
[[ADR-0001 Credenciales en almacén nativo del SO]]; esta ADR fija cómo se
autentica la conexión SSH y cómo se verifica el servidor.

## Decisión

- **Preferir clave sobre contraseña.** Por defecto **ed25519** (ECDSA P-256 como
  alternativa; RSA-3072+ solo como fallback). Las contraseñas, cuando se usen, se
  custodian según la ADR-0001 y pueden protegerse con biometría.
- **Verificación de host key.** Comprobar el servidor con `known_hosts` (modelo
  TOFU con confirmación del usuario en el primer contacto) para evitar MITM
  silenciosos. sshj aporta `OpenSSHKnownHosts`.
- **Clave no exportable en hardware (objetivo desde la v1).** La clave privada se
  genera y reside en el almacén de claves del SO (Android Keystore / StrongBox
  cuando exista), es **no exportable** y firma el challenge de autenticación sin
  salir del hardware. Se integra mediante un *signer* personalizado que sshj (o
  MINA) delega en el almacén de claves.

## Alternativas consideradas

- **Guardar la clave privada en el almacén de secretos y cargarla en memoria** —
  más simple, pero la clave llega a exponerse en el proceso; se descarta como meta
  de la v1 (queda como fallback en plataformas sin soporte de hardware).
- **Autenticación por contraseña como principal** — descartada por seguridad.

## Consecuencias

- Positivas: la clave privada nunca se expone; verificación de host evita MITM;
  alineado con las mejores prácticas SSH.
- Negativas / compromisos: el signer delegado en hardware es más trabajo de
  integración y su disponibilidad depende de la plataforma (fallback necesario
  donde no haya hardware o formato de clave soportado).
