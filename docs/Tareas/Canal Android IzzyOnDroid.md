---
Nombre: 'Canal Android IzzyOnDroid'
Estado: 'Pendiente'
Resumen: 'Canal público y gratuito de Android. IzzyOnDroid es un repositorio compatible con F-Droid que toma la APK de los GitHub Releases y la sirve con actualizaciones automáticas a los clientes F-Droid, Droid-ify y Neo Store. Requisitos a cumplir y comprobar: licencia libre, APK en Releases, metadatos fastlane en el repositorio, sin trackers ni bloque de dependencias de AGP y un tamaño aceptable. Riesgo a resolver con ellos: su escáner puede marcar los binarios ELF del agente empaquetados como recursos. Se solicita la inclusión abriendo una incidencia en su repositorio.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4. F-Droid oficial queda aplazado.'
Bloqueada:
  - "[[Licencia GPL-3.0-or-later del proyecto]]"
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Canal Android IzzyOnDroid

## Objetivo

Que cualquier usuario de Android pueda instalar y actualizar titan-ssh sin Google
Play y sin coste para el proyecto.

## Criterios de finalización

- Comprobar en su documentación los **requisitos vigentes** al abordarla
  (licencia libre, sin componentes privativos ni trackers, límite de tamaño de
  la APK, cadencia de actualización) y anotarlos aquí.
- Metadatos en el repositorio con estructura fastlane
  (`fastlane/metadata/android/<idioma>/`: `title.txt`,
  `short_description.txt`, `full_description.txt`,
  `changelogs/<versionCode>.txt`, `images/icon.png` y capturas), al menos en
  español e inglés. Los mismos textos servirán para Play.
- `dependenciesInfo` desactivado (ver
  [[Firma y configuración de release Android]]).
- **Binarios del agente**: la APK lleva ejecutables ELF/Mach-O/PE en
  `/agent/` (se suben a los hosts remotos; no se ejecutan en el teléfono).
  Explicarlo en la solicitud; si el escáner lo marca, valorar con ellos las
  opciones (que se compilen desde fuente, descarga bajo demanda según
  [[ADR-0010 Empaquetado del agente y descarga bajo demanda]], etc.). La descarga
  de binarios en tiempo de ejecución también debe declararse.
- Solicitud enviada y app publicada; comprobar que una versión nueva de GitHub
  Releases llega a los clientes.
- README: insignia o enlace de IzzyOnDroid.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
