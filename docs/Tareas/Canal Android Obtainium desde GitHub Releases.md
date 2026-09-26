---
Nombre: 'Canal Android Obtainium desde GitHub Releases'
Estado: 'En curso'
Resumen: 'Sustituir el pasar la APK a mano por actualizaciones automáticas. El usuario instala Obtainium en el Pixel, lo apunta a los GitHub Releases del repositorio (con pre-releases activados para el canal interno) y cada tag le llega como actualización. Es gratis y no hace falta cuenta de Google Play. Incluye documentar en el README cómo instalar la app en Android y verificar el ciclo completo de actualización.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4.'
Bloqueada:
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-26T17:45:00+02:00
---

# Canal Android Obtainium desde GitHub Releases

## Objetivo

Que el usuario reciba cada versión de prueba en el móvil como una actualización
normal, sin pasar APK por Tailscale.

## Contexto

Obtainium (open source, instalable desde su GitHub o IzzyOnDroid) consulta los
Releases de un repositorio y descarga e instala la APK que coincida. Solo lo
instala el usuario; no requiere nada en el repositorio más allá de publicar la
APK en cada Release. Hoy la APK se pasa a mano (flujo de Tailscale en
[[ssh-test-host]]).

## Criterios de finalización

- La APK del Release tiene un nombre estable y reconocible (p. ej.
  `titan-ssh-<versión>.apk`), para que el filtro de Obtainium no sea ambiguo
  con el AAB u otros artefactos.
- README: sección "Instalar en Android" con enlace a Obtainium, la URL del
  repositorio y la opción de incluir pre-releases.
- Probado en el Pixel: instalar la versión N desde Obtainium, publicar N+1 y
  ver que Obtainium la detecta y actualiza **conservando los datos** (misma
  firma y `versionCode` mayor).
- La app de desarrollo instalada a mano tiene otro `applicationId` tras
  [[Cambiar el identificador de la app a io.github]]; documentar que conviven o
  que hay que desinstalar la vieja.

## Notas

- Google exige verificación de desarrolladores también para apps instaladas
  fuera de Play en dispositivos certificados, con despliegue por países desde
  2026 (a España le llegaría previsiblemente en 2027) y un nivel gratuito para
  aficionados. Cuando aplique, registrar el `applicationId` y la clave; se
  seguirá con ello en una tarea aparte.

## Verificación

<Se rellena al completar.>

## Resultado

Hecho el 2026-09-26, a falta de la prueba en el Pixel:

- La APK se publica como `titan-ssh-<versión>.apk` y es el único `.apk` del
  Release (el AAB lleva otra extensión), así que Obtainium no tiene que elegir.
  La publica [[Pipeline de release en GitHub Actions]], que además comprueba
  la huella del certificado antes de publicarla.
- README: sección **Instalar → Android**, con Obtainium, la URL del
  repositorio, la opción de incluir pre-releases y la huella SHA-256 del
  certificado. Explica que una APK de debug compilada en local no se puede
  instalar encima de la publicada, y que las versiones antiguas
  `im.gar.titanssh` son otra app que convive con esta y se desinstala aparte.
- En el Pixel está instalada `0.1.0-beta.1`, enviada a mano y firmada con la
  clave de release. Si el primer Release es `v0.1.0-beta.1`, Obtainium debería
  reconocerla como instalada, y `v0.1.0-beta.2` sirve de prueba de
  actualización.
