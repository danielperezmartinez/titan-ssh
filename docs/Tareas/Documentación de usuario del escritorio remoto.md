---
Nombre: 'Documentación de usuario del escritorio remoto'
Estado: 'Pendiente'
Resumen: 'Última fase del escritorio remoto: documentación para usuarios de en qué sistemas funciona (Windows, X11, KDE, GNOME y wlroots en Wayland), qué requisitos tiene cada uno (sesión iniciada, diálogo del portal, acceso desatendido, versiones), qué no se puede (Windows bloqueado, UAC) y qué significa cada error. Por decidir al llegar a ella: si va en la página de GitHub Pages que ya existe (site/) o en una sección de documentación nueva.'
Decisiones: 'Pedida por el usuario el 2026-10-21 al planificar [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]. Relacionada con [[Página de producto en GitHub Pages]].'
Bloqueada:
  - "[[Escritorio remoto en destinos Linux Wayland]]"
  - "[[Escritorio remoto en destinos Linux wlroots]]"
  - "[[Escritorio remoto en destinos Windows]]"
Versión: ''
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-21T18:55:00+02:00
---

# Documentación de usuario del escritorio remoto

Parte de [[Escritorio remoto a través del agente]].

## Objetivo

Que quien use titan-ssh sepa, sin leer el código ni esta bóveda, si su
destino admite el escritorio remoto, qué tiene que preparar y qué hacer ante
cada error.

## Contenido previsto

- Sistemas admitidos y vía de cada uno (X11, portal en KDE y GNOME, wlroots,
  Windows), con versiones mínimas.
- Requisitos: sesión iniciada en el destino, el diálogo del portal y el
  acceso desatendido, el ayudante de Windows.
- Límites: pantalla de bloqueo y UAC en Windows, varios monitores, HiDPI.
- Cada error de la app con su causa y qué hacer.

## Por decidir

Dónde vive: una sección o página nueva en el sitio de GitHub Pages actual
(`site/`), o un sitio de documentación aparte. Se decide con el usuario al
empezar esta fase.

## Criterios de finalización

- Publicada y revisada por el usuario.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
