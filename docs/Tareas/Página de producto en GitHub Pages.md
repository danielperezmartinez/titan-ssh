---
Nombre: 'Página de producto en GitHub Pages'
Estado: 'Hecha'
Resumen: 'Web de producto de titan-ssh en GitHub Pages, en inglés y español, con la estética de la app (mono en todo, canvas oscuro, marcadores ASCII). Ofrece las descargas de la última versión estable sin pasar por la lista de assets del Release: la página lee la API pública de GitHub Releases al cargar y destaca el paquete del sistema del visitante. Solo versión estable, sin canary.'
Decisiones: 'Página estática sin framework ni build, en site/, desplegada con un workflow de GitHub Pages (coste cero). Las descargas salen de releases/latest en el navegador, así que la web no se toca al publicar. Solo canales vivos: descargas directas y Obtainium; winget, AUR, Flathub e IzzyOnDroid se añaden cuando estén publicados.'
Bloqueada: []
Versión: ''
Fecha de creación: '2026-10-20T12:00:00+02:00'
Última modificación: '2026-10-20T20:25:00+02:00'
---

# Página de producto en GitHub Pages

## Objetivo

Que cualquiera pueda descargar titan-ssh desde una página propia, con el
paquete de su sistema destacado, sin tener que buscar en la lista de ficheros
de GitHub Releases. De paso, una página de producto que explique qué es
titan-ssh y por qué existe, con la estética de la app
([[Tokens visuales dark-first base opencode]],
[[Vocabulario ASCII ampliado y disciplina de color]],
[[Icono de la app T que sobrevive al microcorte]]).

## Criterios de finalización

- Página en `site/` en inglés y español, con selector de idioma.
- Las descargas vienen de la última versión estable (`releases/latest`) y se
  destaca el paquete del sistema detectado; si la API falla, enlaza al Release.
- Workflow de GitHub Pages que publica `site/` desde `main`.
- Responsive hasta móvil, foco visible y `prefers-reduced-motion` respetado.
- El usuario la revisa y aprueba antes de activar Pages.

## Estado

- 2026-10-20: primera versión en `site/` (`index.html`, `styles.css`,
  `app.js`, JetBrains Mono en woff2 con su OFL, `icon.svg` copiado de
  `branding/`) y workflow `.github/workflows/pages.yml`. `release.yml` ignora
  `site/**` y `pages.yml` para que tocar la web no construya la canary.
  Revisada con capturas en escritorio y móvil, con la API real (1.2.0).
  Vista previa para el usuario publicada como artifact; el usuario la aprobó.
- 2026-10-20: subida a `main` y Pages activado con GitHub Actions como fuente.

## Verificación

- Capturas en escritorio (1440 px) y móvil (390 px), en inglés y español,
  con el sistema detectado (Linux, Windows y Android) y la API real.
- El primer run del workflow `Pages` terminó bien y
  https://danielperezmartinez.github.io/titan-ssh/ sirve la página, los
  estilos, el script, las fuentes y el icono (HTTP 200). La captura de la web
  publicada muestra la 1.2.0 con los tamaños y enlaces directos a los assets.

## Resultado

Web de producto estática en `site/`, publicada en
https://danielperezmartinez.github.io/titan-ssh/ por
`.github/workflows/pages.yml` en cada push a `main` que toque `site/`. Las
descargas salen de `releases/latest` en el navegador, así que publicar una
versión no necesita tocar la web. Cuando winget, AUR, Flathub o IzzyOnDroid
estén publicados, se añaden a la sección de descargas de `index.html` y a
los textos de `app.js`.
