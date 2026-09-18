---
Nombre: "Gestión de claves y secretos (UI)"
Estado: Pendiente
Resumen: 'Aprovisionar material de autenticación desde la app para que los métodos de auth del host sean usables de punta a punta. Falta la UI que genera una clave hardware no exportable (y muestra su línea authorized_keys para enrolar), importa/genera una clave software y guarda contraseñas/passphrases en el SecretStore. Hoy el editor de host solo REFERENCIA material que nada crea; sin esto no se puede probar una conexión real desde la app.'
Decisiones: Consume [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]]. Se apoya en la fundación de [[Almacenamiento seguro de credenciales]] y el signer de [[Autenticación SSH signer en hardware y verificación de host]].
Bloqueada: []
Fecha de creación: 2026-09-18T16:10:00+02:00
Última modificación: 2026-09-18T16:10:00+02:00
---

# Gestión de claves y secretos (UI)

## Objetivo

Cerrar el hueco de aprovisionamiento: que el usuario pueda **crear/guardar** el
material de autenticación desde la app, no solo referenciarlo. Hoy el editor de
host ([[Panel de gestión de hosts y sesiones]]) solo acepta un *nombre de
referencia* (SecretStore) o un *alias* del almacén del SO, pero **nada crea ese
material**, así que ningún método de auth se completa de punta a punta y no se
puede probar una conexión real con el [[Terminal multipestaña con sesiones simultáneas]].

La capacidad de bajo nivel ya existe: `AndroidHardwareKeys.ensureKey(alias)`
genera la clave EC P-256 no exportable y expone su línea `authorized_keys`
(verificado en dispositivo por la pantalla de depuración
`HardwareSignerTestScreen`, que no está cableada en la UI de producto). Esta tarea
lleva esa capacidad a la UI real y añade el aprovisionamiento de secretos software.

## Criterios de finalización

- **Clave hardware (objetivo v1, Android)**: desde la sección "Clave hardware" del
  editor de host, una acción **Generar / mostrar clave** que:
  - genere (o reutilice) la clave no exportable en el almacén del SO **en el alias
    elegido** (no el alias fijo del banco de pruebas), vía un seam `expect`/`actual`
    para poder invocarla desde `commonMain` (androidMain sobre `AndroidHardwareKeys`;
    en escritorio no hay clave hardware no exportable en v1 → acción no disponible).
  - muestre y permita **copiar la línea `authorized_keys`** para enrolarla en el
    servidor.
- **Contraseña**: un campo para introducir la contraseña y **guardarla en el
  SecretStore** bajo la referencia que usa el host (hoy la referencia no resuelve
  porque nada la escribe).
- **Clave software**: **importar** una clave privada existente (PEM) —y opcional:
  **generar** un par ed25519— guardando la privada en el SecretStore bajo su
  referencia y mostrando la línea pública para enrolar. La passphrase, si la hay,
  también al SecretStore.
- **Sin texto plano**: todo el material sensible va al `SecretStore`; el documento
  de config sigue guardando solo referencias/alias (ADR-0001). La clave hardware
  nunca sale del almacén.
- La UI respeta el lenguaje visual dark-first (ver [[Componentes UI compartidos]] /
  [[Vocabulario ASCII ampliado y disciplina de color]]).

## Verificación

<Se rellena al completar: build de ambos targets, tests, y comprobación real en el
pixel-9-pro-xl generando la clave hardware, enrolándola en el host de pruebas y
conectando desde el terminal.>

## Resultado

<Se rellena al completar.>
