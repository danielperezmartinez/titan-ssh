---
Nombre: 'Documentación de usuario del escritorio remoto'
Estado: 'Hecha'
Estado desde: 2026-10-28
Resumen: 'Última fase del escritorio remoto: documentación para usuarios de en qué sistemas funciona (Windows, X11, KDE, GNOME y wlroots en Wayland), qué requisitos tiene cada uno (sesión iniciada, diálogo del portal, acceso desatendido, versiones), qué no se puede (Windows bloqueado, UAC) y qué significa cada error. Va en una página nueva del sitio de GitHub Pages, site/remote-desktop.html, en inglés y español, enlazada como «Docs» desde el menú de la portada.'
Decisiones: 'Pedida por el usuario el 2026-10-21 al planificar [[ADR-0031 Escritorio remoto en Wayland, Windows y casos de borde]]. Relacionada con [[Página de producto en GitHub Pages]]. El 2026-10-22 el usuario elige: página nueva en site/ (no una sección de la portada ni un sitio aparte), en inglés y español como la portada, con la estructura aviso de experimental, qué es, tabla de sistemas, requisitos, límites y errores. Los dos idiomas van en la misma página (data-l) y un docs.js pequeño elige el idioma con la misma clave que la portada; app.js no cambia salvo el texto del enlace.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-21T18:55:00+02:00
Última modificación: 2026-10-28T12:00:00+01:00
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

## Decidido (2026-10-22)

- **Dónde**: `site/remote-desktop.html`, con los estilos de `site/styles.css`
  (sección «Documentation pages») y un enlace «Docs» / «Documentación» en el
  menú de la portada. Se publica con el workflow `Pages` al llegar a `main`.
- **Idioma**: inglés y español en la misma página; `site/docs.js` elige el
  idioma (`?lang=`, la elección guardada de la portada o el del navegador).
  Como la app solo está en español, la versión inglesa cita sus textos en
  español con su significado.
- **Estructura**: aviso de experimental (solo canary, con enlace al Release
  `canary`), qué es, tabla de sistemas, requisitos, límites y errores.
- Las versiones de Linux se dan como «probado con», no como mínimos: no hay
  un mínimo fijado. El de Windows es el del agente (10 1809).

## Mantenimiento

La página dice qué está solo en la canary. Hay que tocarla cuando cambie:

- El mouse pad con Linux X11 llega a la estable con la próxima versión, y
  con Wayland cuando deje de ser solo de la canary.
- El escritorio remoto deja de ser experimental (o se retira, según la
  comparación con el cliente RDP de ADR-0028).
- [[Teclear acentos y AltGr en destinos Wayland con portal]] se resuelve.
- Se prueba Hyprland o se puede elegir un monitor.

## Criterios de finalización

- Publicada y revisada por el usuario.

## Verificación

2026-10-22, en local (`python3 -m http.server` sobre `site/` y Chromium
headless): capturas a 1280 px y 390 px, en español y en inglés, fuera del
repositorio. Sin desbordamiento horizontal de la página; en el móvil la tabla
de sistemas se desplaza dentro de su caja. La portada enseña el enlace nuevo.
Revisada y aprobada por el usuario el 2026-10-22 («Está perfecto»), con la
tabla desplazable en el móvil, el aviso de seguridad del acceso desatendido y
sin hablar del aviso en pantalla del destino. Pendiente: la integración en
`main` y comprobar la página publicada en GitHub Pages; entonces pasa a
`Hecha`.

2026-10-28: la página está en `main` y el workflow `Pages` acabó bien (run
`39828455285`, 2026-10-22). La página publicada (`remote-desktop.html`)
responde 200, es idéntica byte a byte a `site/remote-desktop.html` de `main`
y la portada publicada la enlaza.

## Resultado

Página `site/remote-desktop.html` en el sitio de GitHub Pages, en inglés y
español, enlazada como «Docs» desde el menú de la portada: sistemas en los que
funciona el escritorio remoto y el mouse pad, requisitos de cada uno, límites
y significado de cada error.
