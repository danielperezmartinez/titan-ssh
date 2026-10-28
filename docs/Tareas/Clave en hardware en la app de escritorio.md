---
Nombre: 'Clave en hardware en la app de escritorio'
Estado: 'Pendiente'
Estado desde: 2026-10-28
Resumen: 'En la app de escritorio (Windows y Linux) la autenticación con clave no exportable en hardware sale como «aún no disponible»: solo Android la tiene (Keystore/StrongBox). El usuario quiere abordarla cuanto antes. Hay que elegir el almacén de cada sistema (TPM, agente SSH con llave FIDO u otro) y delegar la firma como en Android.'
Decisiones: 'Sigue [[ADR-0005 Autenticación SSH y verificación de host]], que fija la clave no exportable como objetivo desde la v1 y deja el PEM en software como alternativa donde no hay hardware. La firma delegada de Android está en [[Autenticación SSH signer en hardware y verificación de host]]. El usuario la pide con prioridad el 2026-10-28, al probar la canary.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T17:30:00+01:00
Última modificación: 2026-10-28T17:30:00+01:00
---

# Clave en hardware en la app de escritorio

## Objetivo

Que en la app de escritorio se pueda conectar con una clave privada que no
sale del hardware del equipo, igual que en Android, en lugar del aviso «aún no
disponible» de hoy (`HostEditor`: en escritorio la acción no está disponible).

## Criterios de finalización

- Vía elegida para Windows y para Linux, con su ADR si cambia lo decidido en
  ADR-0005.
- Generar la clave, enseñar su línea pública e instalarla en el host desde la
  app (como en [[Instalar la clave pública en el host desde la app]]), y
  conectar firmando en hardware.
- Probado por el usuario en un Windows y un Linux reales.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
