---
Nombre: 'Canal Android Obtainium desde GitHub Releases'
Estado: 'Hecha'
Resumen: 'Sustituir el pasar la APK a mano por la descarga desde los GitHub Releases del repositorio. Cada Release publica una sola APK con nombre estable (titan-ssh-<versión>.apk) y firmada con la clave de release, que CI comprueba, así que Obtainium puede seguirlo sin configuración especial. El README documenta cómo instalar con Obtainium (con pre-releases) o descargando la APK. El usuario decidió no instalar Obtainium en su móvil: descarga la APK del Release e instala encima, y así se verificó con v0.1.0-beta.1.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-26T18:34:00+02:00
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

2026-09-26. **Decisión del usuario: no instala Obtainium en su móvil**, porque
no se siente cómodo con él. En su lugar descarga la APK directamente del
Release de GitHub. Por eso el ciclo con Obtainium no se probó. Lo que sí se
comprobó:

- La APK publicada en
  [`v0.1.0-beta.1`](https://github.com/danielperezmartinez/titan-ssh/releases/tag/v0.1.0-beta.1)
  se instaló en el Pixel **encima** de la que tenía (enviada a mano y firmada
  con la misma clave). La app se actualizó conservando los datos, ya muestra
  el icono y funciona bien.
- La APK es el único `.apk` del Release y tiene nombre estable, y CI comprueba
  que está firmada con la clave de release. Son las condiciones para que
  Obtainium (u otro instalador que lea Releases) funcione sin configuración
  especial.
- La actualización con un `versionCode` mayor se prueba con `v0.1.0-beta.2`
  (registro en [[Seguimiento de tareas pendientes]]).

## Resultado

- La APK se publica como `titan-ssh-<versión>.apk` y es el único `.apk` del
  Release (el AAB lleva otra extensión). La publica
  [[Pipeline de release en GitHub Actions]], que además comprueba la huella del
  certificado antes de publicarla.
- README: sección **Instalar → Android**, con Obtainium (URL del repositorio y
  opción de incluir pre-releases), la descarga directa de la APK del Release y
  la huella SHA-256 del certificado. Explica que una APK de debug compilada en
  local no se puede instalar encima de la publicada, y que las versiones
  antiguas `im.gar.titanssh` son otra app que convive con esta y se
  desinstala aparte.
- Obtainium sigue como canal documentado para quien lo quiera
  ([[ADR-0011 Distribución y canales de publicación]] no cambia). El usuario
  actualiza a mano desde Releases, así que no le llega aviso de las versiones
  nuevas hasta [[Aviso de nueva versión en la app]]. Esa tarea se queda en el
  paso 14, por decisión suya: mientras sea el único usuario, ya sabe cuándo
  sale cada versión.
