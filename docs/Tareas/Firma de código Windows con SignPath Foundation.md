---
Nombre: 'Firma de código Windows con SignPath Foundation'
Estado: 'Pendiente'
Resumen: 'Quitar el aviso de "editor desconocido" de SmartScreen sin pagar un certificado. SignPath Foundation da firma de código gratis a proyectos open source a cambio de firmar solo lo que compila su CI a partir del código fuente público. Hay que solicitarlo cuando el proyecto cumpla sus requisitos (licencia OSI, releases publicados, proyecto mantenido, política de firma en el repositorio), integrarlo en el pipeline con su acción de GitHub y firmar el MSI y, si se puede, el lanzador .exe que contiene.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §5. Alternativa si no se concede: MSIX en Microsoft Store (cuenta gratis), ver la ADR.'
Bloqueada:
  - "[[Licencia GPL-3.0-or-later del proyecto]]"
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Firma de código Windows con SignPath Foundation

## Objetivo

Instaladores de Windows firmados, gratis, para que SmartScreen no bloquee la
instalación a usuarios públicos.

## Criterios de finalización

- Comprobar en signpath.org los **requisitos vigentes** y anotarlos aquí:
  licencia OSI, repositorio público, versiones ya publicadas, actividad,
  MFA de los miembros, página de política de firma en el repositorio, etc.
- Solicitud enviada por el usuario y aprobada.
- Integración en [[Pipeline de release en GitHub Actions]]: el artefacto sin
  firmar se sube como artefacto del workflow, la acción de SignPath lo envía a
  firmar y el MSI firmado es el que se publica en el Release (y el que usa
  [[Canal Windows winget]]).
- Decidir y probar el **orden de firma**: el `.exe` lanzador de jpackage va
  dentro del MSI; lo ideal es firmar el `.exe` de la imagen de
  `createDistributable` antes de empaquetar el MSI, o usar la firma en
  profundidad de SignPath si la ofrece para MSI.
- Comprobar en un Windows limpio que las propiedades del MSI muestran la firma y
  que SmartScreen no muestra el aviso de "editor desconocido". La reputación
  puede tardar en acumularse; anotarlo.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
